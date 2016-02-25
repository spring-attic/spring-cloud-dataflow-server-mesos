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

package org.springframework.cloud.dataflow.server.spi.marathon;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.Cloud;
import org.springframework.cloud.CloudFactory;
import org.springframework.cloud.dataflow.module.deployer.ModuleDeployer;
import org.springframework.cloud.dataflow.module.deployer.marathon.MarathonModuleDeployer;
import org.springframework.cloud.dataflow.module.deployer.marathon.MarathonProperties;
import org.springframework.cloud.dataflow.server.config.DataFlowServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Configuration used when running on Marathon.
 *
 * @author Eric Bottard
 * @author Ilayaperumal Gopinathan
 */
@Configuration
@EnableConfigurationProperties({MarathonProperties.class, DataFlowServerProperties.class})
public class MarathonAutoConfiguration {
	protected static class MarathonConfig {

		@Bean
		public ModuleDeployer processModuleDeployer(MarathonProperties marathonProperties,
		                                            DataFlowServerProperties serverProperties) {
			return new MarathonModuleDeployer(marathonProperties, serverProperties);
		}

		@Bean
		public ModuleDeployer taskModuleDeployer(MarathonProperties marathonProperties,
		                                         DataFlowServerProperties serverProperties) {
			return processModuleDeployer(marathonProperties, serverProperties);
		}
	}

	@Profile("cloud")
	@AutoConfigureBefore(RedisAutoConfiguration.class)
	protected static class RedisConfig {

		@Bean
		public Cloud cloud(CloudFactory cloudFactory) {
			return cloudFactory.getCloud();
		}

		@Bean
		public CloudFactory cloudFactory() {
			return new CloudFactory();
		}

		@Bean
		RedisConnectionFactory redisConnectionFactory(Cloud cloud) {
			return cloud.getSingletonServiceConnector(RedisConnectionFactory.class, null);
		}
	}
}
