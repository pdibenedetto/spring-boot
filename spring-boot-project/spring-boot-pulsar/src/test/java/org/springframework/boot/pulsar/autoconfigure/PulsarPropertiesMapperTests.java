/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.pulsar.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.api.AutoClusterFailoverBuilder.FailoverPolicy;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.HashingScheme;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.ProducerAccessMode;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClientException.UnsupportedAuthenticationException;
import org.apache.pulsar.client.api.ReaderBuilder;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.AutoClusterFailover;
import org.apache.pulsar.common.schema.SchemaType;
import org.junit.jupiter.api.Test;

import org.springframework.boot.pulsar.autoconfigure.PulsarProperties.Consumer;
import org.springframework.boot.pulsar.autoconfigure.PulsarProperties.Failover.BackupCluster;
import org.springframework.pulsar.core.PulsarProducerFactory;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.pulsar.listener.PulsarContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link PulsarPropertiesMapper}.
 *
 * @author Chris Bono
 * @author Phillip Webb
 * @author Swamy Mavuri
 * @author Vedran Pavic
 */
class PulsarPropertiesMapperTests {

	@Test
	void customizeClientBuilderWhenHasNoAuthentication() {
		PulsarProperties properties = new PulsarProperties();
		properties.getClient().setServiceUrl("https://example.com");
		properties.getClient().setConnectionTimeout(Duration.ofSeconds(1));
		properties.getClient().setOperationTimeout(Duration.ofSeconds(2));
		properties.getClient().setLookupTimeout(Duration.ofSeconds(3));
		properties.getClient().getThreads().setIo(3);
		properties.getClient().getThreads().setListener(10);
		ClientBuilder builder = mock(ClientBuilder.class);
		new PulsarPropertiesMapper(properties).customizeClientBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should().serviceUrl("https://example.com");
		then(builder).should().connectionTimeout(1000, TimeUnit.MILLISECONDS);
		then(builder).should().operationTimeout(2000, TimeUnit.MILLISECONDS);
		then(builder).should().lookupTimeout(3000, TimeUnit.MILLISECONDS);
		then(builder).should().ioThreads(3);
		then(builder).should().listenerThreads(10);
	}

	@Test
	void customizeClientBuilderWhenHasAuthentication() throws UnsupportedAuthenticationException {
		PulsarProperties properties = new PulsarProperties();
		Map<String, String> params = Map.of("simpleParam", "foo", "complexParam",
				"{\n\t\"k1\" : \"v1\",\n\t\"k2\":\"v2\"\n}");
		String authParamString = "{\"complexParam\":\"{\\n\\t\\\"k1\\\" : \\\"v1\\\",\\n\\t\\\"k2\\\":\\\"v2\\\"\\n}\""
				+ ",\"simpleParam\":\"foo\"}";
		properties.getClient().getAuthentication().setPluginClassName("myclass");
		properties.getClient().getAuthentication().setParam(params);
		ClientBuilder builder = mock(ClientBuilder.class);
		new PulsarPropertiesMapper(properties).customizeClientBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should().authentication("myclass", authParamString);
	}

	@Test
	void customizeClientBuilderWhenTransactionEnabled() {
		PulsarProperties properties = new PulsarProperties();
		properties.getTransaction().setEnabled(true);
		ClientBuilder builder = mock(ClientBuilder.class);
		new PulsarPropertiesMapper(properties).customizeClientBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should().enableTransaction(true);
	}

	@Test
	void customizeClientBuilderWhenTransactionDisabled() {
		PulsarProperties properties = new PulsarProperties();
		properties.getTransaction().setEnabled(false);
		ClientBuilder builder = mock(ClientBuilder.class);
		new PulsarPropertiesMapper(properties).customizeClientBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should(never()).enableTransaction(anyBoolean());
	}

	@Test
	void customizeClientBuilderWhenHasConnectionDetails() {
		PulsarProperties properties = new PulsarProperties();
		properties.getClient().setServiceUrl("https://ignored.example.com");
		ClientBuilder builder = mock(ClientBuilder.class);
		PulsarConnectionDetails connectionDetails = mock(PulsarConnectionDetails.class);
		given(connectionDetails.getBrokerUrl()).willReturn("https://used.example.com");
		new PulsarPropertiesMapper(properties).customizeClientBuilder(builder, connectionDetails);
		then(builder).should().serviceUrl("https://used.example.com");
	}

	@Test
	void customizeClientBuilderWhenHasFailover() {
		BackupCluster backupCluster1 = new BackupCluster();
		backupCluster1.setServiceUrl("backup-cluster-1");
		Map<String, String> params = Map.of("param", "name");
		backupCluster1.getAuthentication().setPluginClassName(MockAuthentication.class.getName());
		backupCluster1.getAuthentication().setParam(params);
		BackupCluster backupCluster2 = new BackupCluster();
		backupCluster2.setServiceUrl("backup-cluster-2");
		PulsarProperties properties = new PulsarProperties();
		properties.getClient().setServiceUrl("https://used.example.com");
		properties.getClient().getFailover().setPolicy(FailoverPolicy.ORDER);
		properties.getClient().getFailover().setCheckInterval(Duration.ofSeconds(5));
		properties.getClient().getFailover().setDelay(Duration.ofSeconds(30));
		properties.getClient().getFailover().setSwitchBackDelay(Duration.ofSeconds(30));
		properties.getClient().getFailover().setBackupClusters(List.of(backupCluster1, backupCluster2));
		PulsarConnectionDetails connectionDetails = mock(PulsarConnectionDetails.class);
		given(connectionDetails.getBrokerUrl()).willReturn("https://used.example.com");
		ClientBuilder builder = mock(ClientBuilder.class);
		new PulsarPropertiesMapper(properties).customizeClientBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should().serviceUrlProvider(any(AutoClusterFailover.class));
	}

	@Test
	void customizeAdminBuilderWhenHasNoAuthentication() {
		PulsarProperties properties = new PulsarProperties();
		properties.getAdmin().setServiceUrl("https://example.com");
		properties.getAdmin().setConnectionTimeout(Duration.ofSeconds(1));
		properties.getAdmin().setReadTimeout(Duration.ofSeconds(2));
		properties.getAdmin().setRequestTimeout(Duration.ofSeconds(3));
		PulsarAdminBuilder builder = mock(PulsarAdminBuilder.class);
		new PulsarPropertiesMapper(properties).customizeAdminBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should().serviceHttpUrl("https://example.com");
		then(builder).should().connectionTimeout(1000, TimeUnit.MILLISECONDS);
		then(builder).should().readTimeout(2000, TimeUnit.MILLISECONDS);
		then(builder).should().requestTimeout(3000, TimeUnit.MILLISECONDS);
	}

	@Test
	void customizeAdminBuilderWhenHasAuthentication() throws UnsupportedAuthenticationException {
		PulsarProperties properties = new PulsarProperties();
		Map<String, String> params = Map.of("simpleParam", "foo", "complexParam",
				"{\n\t\"k1\" : \"v1\",\n\t\"k2\":\"v2\"\n}");
		String authParamString = "{\"complexParam\":\"{\\n\\t\\\"k1\\\" : \\\"v1\\\",\\n\\t\\\"k2\\\":\\\"v2\\\"\\n}\""
				+ ",\"simpleParam\":\"foo\"}";
		properties.getAdmin().getAuthentication().setPluginClassName("myclass");
		properties.getAdmin().getAuthentication().setParam(params);
		PulsarAdminBuilder builder = mock(PulsarAdminBuilder.class);
		new PulsarPropertiesMapper(properties).customizeAdminBuilder(builder,
				new PropertiesPulsarConnectionDetails(properties));
		then(builder).should().authentication("myclass", authParamString);
	}

	@Test
	void customizeAdminBuilderWhenHasConnectionDetails() {
		PulsarProperties properties = new PulsarProperties();
		properties.getAdmin().setServiceUrl("https://ignored.example.com");
		PulsarAdminBuilder builder = mock(PulsarAdminBuilder.class);
		PulsarConnectionDetails connectionDetails = mock(PulsarConnectionDetails.class);
		given(connectionDetails.getAdminUrl()).willReturn("https://used.example.com");
		new PulsarPropertiesMapper(properties).customizeAdminBuilder(builder, connectionDetails);
		then(builder).should().serviceHttpUrl("https://used.example.com");
	}

	@Test
	@SuppressWarnings("unchecked")
	void customizeProducerBuilder() {
		PulsarProperties properties = new PulsarProperties();
		properties.getProducer().setName("name");
		properties.getProducer().setTopicName("topicname");
		properties.getProducer().setSendTimeout(Duration.ofSeconds(1));
		properties.getProducer().setMessageRoutingMode(MessageRoutingMode.RoundRobinPartition);
		properties.getProducer().setHashingScheme(HashingScheme.JavaStringHash);
		properties.getProducer().setBatchingEnabled(false);
		properties.getProducer().setChunkingEnabled(true);
		properties.getProducer().setCompressionType(CompressionType.SNAPPY);
		properties.getProducer().setAccessMode(ProducerAccessMode.Exclusive);
		ProducerBuilder<Object> builder = mock(ProducerBuilder.class);
		new PulsarPropertiesMapper(properties).customizeProducerBuilder(builder);
		then(builder).should().producerName("name");
		then(builder).should().topic("topicname");
		then(builder).should().sendTimeout(1000, TimeUnit.MILLISECONDS);
		then(builder).should().messageRoutingMode(MessageRoutingMode.RoundRobinPartition);
		then(builder).should().hashingScheme(HashingScheme.JavaStringHash);
		then(builder).should().enableBatching(false);
		then(builder).should().enableChunking(true);
		then(builder).should().compressionType(CompressionType.SNAPPY);
		then(builder).should().accessMode(ProducerAccessMode.Exclusive);
	}

	@Test
	@SuppressWarnings("unchecked")
	void customizeTemplate() {
		PulsarProperties properties = new PulsarProperties();
		properties.getTransaction().setEnabled(true);
		PulsarTemplate<Object> template = new PulsarTemplate<>(mock(PulsarProducerFactory.class));
		new PulsarPropertiesMapper(properties).customizeTemplate(template);
		assertThat(template.transactions().isEnabled()).isTrue();
	}

	@Test
	@SuppressWarnings("unchecked")
	void customizeConsumerBuilder() {
		PulsarProperties properties = new PulsarProperties();
		List<String> topics = List.of("mytopic");
		Pattern topisPattern = Pattern.compile("my-pattern");
		properties.getConsumer().setName("name");
		properties.getConsumer().setTopics(topics);
		properties.getConsumer().setTopicsPattern(topisPattern);
		properties.getConsumer().setPriorityLevel(123);
		properties.getConsumer().setReadCompacted(true);
		Consumer.DeadLetterPolicy deadLetterPolicy = new Consumer.DeadLetterPolicy();
		deadLetterPolicy.setDeadLetterTopic("my-dlt");
		deadLetterPolicy.setMaxRedeliverCount(1);
		properties.getConsumer().setDeadLetterPolicy(deadLetterPolicy);
		ConsumerBuilder<Object> builder = mock(ConsumerBuilder.class);
		new PulsarPropertiesMapper(properties).customizeConsumerBuilder(builder);
		then(builder).should().consumerName("name");
		then(builder).should().topics(topics);
		then(builder).should().topicsPattern(topisPattern);
		then(builder).should().priorityLevel(123);
		then(builder).should().readCompacted(true);
		then(builder).should().deadLetterPolicy(new DeadLetterPolicy(1, null, "my-dlt", null, null, null));
	}

	@Test
	void customizeContainerProperties() {
		PulsarProperties properties = new PulsarProperties();
		properties.getConsumer().getSubscription().setType(SubscriptionType.Shared);
		properties.getConsumer().getSubscription().setName("my-subscription");
		properties.getListener().setSchemaType(SchemaType.AVRO);
		properties.getListener().setConcurrency(10);
		properties.getListener().setObservationEnabled(true);
		properties.getTransaction().setEnabled(true);
		PulsarContainerProperties containerProperties = new PulsarContainerProperties("my-topic-pattern");
		new PulsarPropertiesMapper(properties).customizeContainerProperties(containerProperties);
		assertThat(containerProperties.getSubscriptionType()).isEqualTo(SubscriptionType.Shared);
		assertThat(containerProperties.getSubscriptionName()).isEqualTo("my-subscription");
		assertThat(containerProperties.getSchemaType()).isEqualTo(SchemaType.AVRO);
		assertThat(containerProperties.getConcurrency()).isEqualTo(10);
		assertThat(containerProperties.isObservationEnabled()).isTrue();
		assertThat(containerProperties.transactions().isEnabled()).isTrue();
	}

	@Test
	@SuppressWarnings("unchecked")
	void customizeReaderBuilder() {
		PulsarProperties properties = new PulsarProperties();
		List<String> topics = List.of("mytopic");
		properties.getReader().setName("name");
		properties.getReader().setTopics(topics);
		properties.getReader().setSubscriptionName("subname");
		properties.getReader().setSubscriptionRolePrefix("subroleprefix");
		properties.getReader().setReadCompacted(true);
		ReaderBuilder<Object> builder = mock(ReaderBuilder.class);
		new PulsarPropertiesMapper(properties).customizeReaderBuilder(builder);
		then(builder).should().readerName("name");
		then(builder).should().topics(topics);
		then(builder).should().subscriptionName("subname");
		then(builder).should().subscriptionRolePrefix("subroleprefix");
		then(builder).should().readCompacted(true);
	}

}
