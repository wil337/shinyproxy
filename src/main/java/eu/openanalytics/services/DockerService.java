/**
 * Copyright 2016 Open Analytics, Belgium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.openanalytics.services;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;

import eu.openanalytics.ShinyProxyException;
import eu.openanalytics.services.AppService.ShinyApp;

@Service
public class DockerService {
		
	private Logger log = Logger.getLogger(DockerService.class);

	private List<Proxy> activeProxies = Collections.synchronizedList(new ArrayList<>());
	private List<MappingListener> mappingListeners = Collections.synchronizedList(new ArrayList<>());
	private Set<Integer> occupiedPorts = Collections.synchronizedSet(new HashSet<>());
	
	private ExecutorService containerKiller = Executors.newSingleThreadExecutor();
	
	@Inject
	Environment environment;
	
	@Inject
	AppService appService;
	
	@Inject
	DockerClient dockerClient;

	public static class Proxy {
		public String name;
		public int port;
		public String containerId;
		public String userName;
		public String appName;
	}
	
	@Bean
	public DockerClient getDockerClient() {
		DockerClientConfig config = DockerClientConfig
				.createDefaultConfigBuilder()
				.withDockerCertPath(environment.getProperty("shiny.proxy.docker.cert-path"))
				.withUri(environment.getProperty("shiny.proxy.docker.url"))
			    .build();
		return DockerClientBuilder.getInstance(config).build();			
	}

	public List<Container> getShinyContainers() {
		List<Container> shinyContainers = new ArrayList<>();
		List<Container> exec = dockerClient.listContainersCmd().exec();
		String imageName = environment.getProperty("shiny.proxy.docker.image-name");
		for (Container container : exec) {
			if (container.getImage().equals(imageName)){
				shinyContainers.add(container);
			}
		}
		return shinyContainers;
	}
	
	public List<Proxy> listProxies() {
		List<Proxy> proxies = new ArrayList<>();
		synchronized (activeProxies) {
			for (Proxy proxy: activeProxies) {
				Proxy copy = new Proxy();
				copy.name = proxy.name;
				copy.port = proxy.port;
				copy.containerId = proxy.containerId;
				copy.userName = proxy.userName;
				copy.appName = proxy.appName;
				proxies.add(copy);
			}
		}
		return proxies;
	}
	
	@PreDestroy
	public void shutdown() {
		containerKiller.shutdown();
		List<Proxy> proxiesToRelease = new ArrayList<>();
		synchronized (activeProxies) {
			proxiesToRelease.addAll(activeProxies);
		}
		for (Proxy proxy: proxiesToRelease) releaseProxy(proxy, false);
	}

	public String getMapping(String userName, String appName) {
		Proxy proxy = findProxy(userName);
		if (proxy == null) {
			// The user has no proxy yet.
			proxy = startProxy(userName, appName);
		} else if (appName.equals(proxy.appName)) {
			// The user's proxy is good to go.
		} else {
			// The user's proxy is running the wrong app.
			releaseProxy(proxy, true);
			proxy = startProxy(userName, appName);
		}
		return (proxy == null) ? null : proxy.name;
	}
	
	public void releaseProxy(String userName) {
		Proxy proxy = findProxy(userName);
		if (proxy != null) releaseProxy(proxy, true);
	}
	
	private void releaseProxy(Proxy proxy, boolean async) {
		activeProxies.remove(proxy);
		Runnable r = new Runnable() {
			@Override
			public void run() {
				try {
					synchronized (dockerClient) {
						dockerClient.stopContainerCmd(proxy.containerId).exec();
						dockerClient.removeContainerCmd(proxy.containerId).exec();
						releasePort(proxy.port);
						log.info(String.format("Proxy released [user: %s] [app: %s] [port: %d]", proxy.userName, proxy.appName, proxy.port));
					}
				} catch (Exception e){
					log.error("Failed to stop container " + proxy.name, e);
				}
			}
		};
		if (async) {
			containerKiller.submit(r);
		} else {
			r.run();
		}
		synchronized (mappingListeners) {
			for (MappingListener listener: mappingListeners) {
				listener.mappingRemoved(proxy.name);
			}
		}
	}
	
	private Proxy startProxy(String userName, String appName) {
		ShinyApp app = appService.getApp(appName);
		if (app == null) {
			throw new ShinyProxyException("Cannot start container: unknown application: " + appName);
		}
		
		Proxy proxy = findProxy(userName);
		if (proxy != null) {
			throw new ShinyProxyException("Cannot start container: user " + userName + " already has a running proxy");
		}
		
		proxy = new Proxy();
		proxy.userName = userName;
		proxy.appName = appName;
		proxy.port = getFreePort();
		
		try {
			ExposedPort tcp3838 = ExposedPort.tcp(3838);
			Ports portBindings = new Ports();
			portBindings.bind(tcp3838, Ports.Binding(proxy.port));
		
			synchronized (dockerClient) {
				CreateContainerResponse container = dockerClient
						.createContainerCmd(app.getDockerImage())
						.withExposedPorts(tcp3838)
						.withPortBindings(portBindings)
						.withCmd(app.getDockerCmd())
						.exec();
				dockerClient.startContainerCmd(container.getId()).exec();
				
				InspectContainerResponse inspectContainerResponse = dockerClient.inspectContainerCmd(container.getId()).exec();
				proxy.name = inspectContainerResponse.getName().substring(1);
				proxy.containerId = container.getId();
			}
		} catch (Exception e) {
			releasePort(proxy.port);
			throw new ShinyProxyException("Failed to start container: " + e.getMessage(), e);
		}

		if (!testContainer(proxy, 20, 500)) {
			releaseProxy(proxy, true);
			throw new ShinyProxyException("Container did not respond in time");
		}
		
		try {
			URI target = new URI("http://" + environment.getProperty("shiny.proxy.docker.host") + ":" + proxy.port);
			synchronized (mappingListeners) {
				for (MappingListener listener: mappingListeners) {
					listener.mappingAdded(proxy.name, target);
				}
			}
		} catch (URISyntaxException ignore) {}
		
		activeProxies.add(proxy);
		log.info(String.format("Proxy activated [user: %s] [app: %s] [port: %d]", userName, appName, proxy.port));
		return proxy;
	}
	
	private Proxy findProxy(String userName) {
		synchronized (activeProxies) {
			for (Proxy proxy: activeProxies) {
				if (userName.equals(proxy.userName)) return proxy;
			}
		}
		return null;
	}
	
	private boolean testContainer(Proxy proxy, int maxTries, int waitMs) {
		for (int currentTry = 1; currentTry <= maxTries; currentTry++) {
			try {
				URL testURL = new URL("http://" + environment.getProperty("shiny.proxy.docker.host") + ":" + proxy.port);
				int responseCode = ((HttpURLConnection) testURL.openConnection()).getResponseCode();
				if (responseCode == 200) return true;
			} catch (Exception e) {
				try { Thread.sleep(waitMs); } catch (InterruptedException ignore) {}
			}
		}
		return false;
	}

	private int getFreePort() {
		int startPort = Integer.valueOf(environment.getProperty("shiny.proxy.docker.port-range-start"));
		int nextPort = startPort;
		while (occupiedPorts.contains(nextPort)) nextPort++;
		occupiedPorts.add(nextPort);
		return nextPort;
	}
	
	private void releasePort(int port) {
		occupiedPorts.remove(port);
	}

	public void addMappingListener(MappingListener listener) {
		mappingListeners.add(listener);
	}
	
	public void removeMappingListener(MappingListener listener) {
		mappingListeners.remove(listener);
	}
	
	public static interface MappingListener {
		public void mappingAdded(String mapping, URI target);
		public void mappingRemoved(String mapping);
	}
}