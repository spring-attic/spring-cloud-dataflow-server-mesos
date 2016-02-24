/*
 * Copyright 2015-16 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.dataflow.module.deployer.marathon;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.dataflow.core.ModuleDeploymentId;
import org.springframework.cloud.dataflow.core.ModuleDeploymentRequest;
import org.springframework.cloud.dataflow.module.ModuleStatus;
import org.springframework.cloud.dataflow.module.deployer.ModuleArgumentQualifier;
import org.springframework.cloud.dataflow.module.deployer.ModuleDeployer;
import org.springframework.cloud.dataflow.server.config.DataFlowServerProperties;

import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Container;
import mesosphere.marathon.client.model.v2.Docker;
import mesosphere.marathon.client.model.v2.Port;
import mesosphere.marathon.client.model.v2.Task;
import mesosphere.marathon.client.utils.MarathonException;

/**
 * A ModuleDeployer implementation for deploying modules as applications on Marathon, using the
 * ModuleLauncher Docker image.
 *
 * @author Eric Bottard
 * @author Ilayaperumal Gopinathan
 */
public class MarathonModuleDeployer implements ModuleDeployer {

	private static Logger log = LoggerFactory.getLogger(MarathonModuleDeployer.class);

	/**
	 * The name of the ENV variable that is added to apps to identify Spring Cloud Data Flow modules.
	 */
	private static final String SPRING_CLOUD_DATAFLOW_MODULE = "SPRING_CLOUD_DATAFLOW_MODULE";

	private final MarathonProperties marathonProperties;

	private final DataFlowServerProperties serverProperties;

	private final Marathon marathon;

	public MarathonModuleDeployer(MarathonProperties marathonProperties, DataFlowServerProperties serverProperties) {
		this.marathonProperties = marathonProperties;
		this.serverProperties = serverProperties;
		marathon = MarathonClient.getInstance(marathonProperties.getApiEndpoint());
	}

	@Override
	public ModuleDeploymentId deploy(ModuleDeploymentRequest request) {
		App app = new App();
		Container container = new Container();
		Docker docker = new Docker();
		docker.setImage(marathonProperties.getImage());
		Port port = new Port(8080);
		port.setHostPort(0);
		docker.setPortMappings(Arrays.asList(port));
		docker.setNetwork("BRIDGE");
		container.setDocker(docker);
		app.setContainer(container);

		app.setId(deduceAppId(request));

		Map<String, String> args = new HashMap<>();
		args.putAll(request.getDeploymentProperties());
		args.putAll(request.getDefinition().getParameters());
		String includes = args.get("includes");
		if (includes != null) {
			includes += "," + marathonProperties.getIncludes();
		}
		else {
			includes = marathonProperties.getIncludes();
		}
		args.put("includes", includes);
		Map<String, String> qualifiedArgs = ModuleArgumentQualifier.qualifyArgs(0, args);
		String jmxDomainName = String.format("%s.%s", request.getDefinition().getGroup(), request.getDefinition().getLabel());
		qualifiedArgs.putAll(ModuleArgumentQualifier.qualifyArgs(0, Collections.singletonMap(JMX_DEFAULT_DOMAIN_KEY, jmxDomainName)));
		Map<String, String> env = new HashMap<>();
		env.putAll(qualifiedArgs);
		env.putAll(serverProperties.asStringProperties());
		env.put("MODULES", request.getCoordinates().toString());
		env.put("spring.profiles.active", "cloud");
		env.put(SPRING_CLOUD_DATAFLOW_MODULE, request.getDefinition().getGroup() + ":" + request.getDefinition().getLabel());

		// Pass API Endpoint under this environment variable for discovery by marathon cloud connector
		env.put("SPRING_CLOUD_MARATHON_HOST", marathonProperties.getApiEndpoint());

		app.setEnv(env);

		Double cpus = deduceCpus(request);
		Double memory = deduceMemory(request);

		app.setCpus(cpus);
		app.setMem(memory);
		app.setInstances(request.getCount());

		log.info("Creating app with definition: " + app.toString());

		try {
			marathon.createApp(app);
		}
		catch (MarathonException e) {
			throw new RuntimeException(e);
		}
		return ModuleDeploymentId.fromModuleDefinition(request.getDefinition());
	}

	@Override
	public void undeploy(ModuleDeploymentId id) {
		String appId = deduceAppId(id);
		try {
			marathon.deleteApp(appId);
		}
		catch (MarathonException e) {
			throw new RuntimeException(e);
		}
	}

	private String deduceAppId(ModuleDeploymentId id) {
		return id.getGroup() + "-" + id.getLabel();
	}

	@Override
	public ModuleStatus status(ModuleDeploymentId id) {
		String appName = deduceAppId(id);
		try {
			App app = marathon.getApp(appName).getApp();
			return buildStatus(id, app);
		}
		catch (MarathonException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Map<ModuleDeploymentId, ModuleStatus> status() {
		Map<ModuleDeploymentId, ModuleStatus> result = new HashMap<>();
		for (App app : marathon.getApps().getApps()) {
			String moduleMarker = app.getEnv().get("SPRING_CLOUD_DATAFLOW_MODULE");
			if (moduleMarker != null) {
				int colon = moduleMarker.indexOf(':');
				String group = moduleMarker.substring(0, colon);
				String label = moduleMarker.substring(colon + 1);
				ModuleDeploymentId id = new ModuleDeploymentId(group, label);
				ModuleStatus status = buildStatus(id, app);
				result.put(id, status);
			}
		}
		return result;
	}

	private ModuleStatus buildStatus(ModuleDeploymentId id, App app) {
		ModuleStatus.Builder result = ModuleStatus.of(id);
		int requestedInstances = app.getInstances();
		int actualInstances = 0;
		if (app.getTasks() != null) {
			for (Task task : app.getTasks()) {
				result.with(MarathonModuleInstanceStatus.up(app, task));
				actualInstances++;
			}
		}
		for (int i = actualInstances; i < requestedInstances; i++) {
			result.with(MarathonModuleInstanceStatus.down(app));
		}
		return result.build();
	}

	private Double deduceMemory(ModuleDeploymentRequest request) {
		String override = request.getDeploymentProperties().get("marathon.memory");
		return override != null ? Double.valueOf(override) : marathonProperties.getMemory();
	}

	private Double deduceCpus(ModuleDeploymentRequest request) {
		String override = request.getDeploymentProperties().get("marathon.cpu");
		return override != null ? Double.valueOf(override) : marathonProperties.getCpu();
	}

	private String deduceAppId(ModuleDeploymentRequest request) {
		return request.getDefinition().getGroup() + "-" + request.getDefinition().getLabel();
	}


}
