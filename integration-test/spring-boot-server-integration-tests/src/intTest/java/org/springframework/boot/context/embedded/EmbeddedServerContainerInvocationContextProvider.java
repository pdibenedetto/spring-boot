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

package org.springframework.boot.context.embedded;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.testsupport.BuildOutput;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriTemplateHandler;

/**
 * {@link TestTemplateInvocationContextProvider} for templated
 * {@link EmbeddedServletContainerTest embedded servlet container tests}.
 *
 * @author Andy Wilkinson
 */
class EmbeddedServerContainerInvocationContextProvider
		implements TestTemplateInvocationContextProvider, AfterAllCallback {

	private static final Set<String> CONTAINERS = new HashSet<>(Arrays.asList("jetty", "tomcat", "undertow"));

	private static final BuildOutput buildOutput = new BuildOutput(
			EmbeddedServerContainerInvocationContextProvider.class);

	private final Map<String, AbstractApplicationLauncher> launcherCache = new HashMap<>();

	private final Path tempDir;

	EmbeddedServerContainerInvocationContextProvider() throws IOException {
		this.tempDir = Files.createTempDirectory("embedded-servlet-container-tests");
	}

	@Override
	public boolean supportsTestTemplate(ExtensionContext context) {
		return true;
	}

	@Override
	public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
		EmbeddedServletContainerTest annotation = context.getRequiredTestClass()
			.getAnnotation(EmbeddedServletContainerTest.class);
		return CONTAINERS.stream()
			.map((container) -> getApplication(annotation, container))
			.flatMap((builder) -> provideTestTemplateInvocationContexts(annotation, builder));
	}

	private Stream<EmbeddedServletContainerInvocationContext> provideTestTemplateInvocationContexts(
			EmbeddedServletContainerTest annotation, Application application) {
		return Stream.of(annotation.launchers())
			.map((launcherClass) -> getAbstractApplicationLauncher(application, launcherClass))
			.map((launcher) -> provideTestTemplateInvocationContext(application, launcher));
	}

	private EmbeddedServletContainerInvocationContext provideTestTemplateInvocationContext(Application application,
			AbstractApplicationLauncher launcher) {
		String name = StringUtils.capitalize(application.getContainer()) + ": "
				+ launcher.getDescription(application.getPackaging());
		return new EmbeddedServletContainerInvocationContext(name, launcher);
	}

	@Override
	public void afterAll(ExtensionContext context) throws Exception {
		cleanupCaches();
		FileSystemUtils.deleteRecursively(this.tempDir);
	}

	private void cleanupCaches() {
		this.launcherCache.values().forEach(AbstractApplicationLauncher::destroyProcess);
		this.launcherCache.clear();
	}

	private AbstractApplicationLauncher getAbstractApplicationLauncher(Application application,
			Class<? extends AbstractApplicationLauncher> launcherClass) {
		String cacheKey = application.getContainer() + ":" + application.getPackaging() + ":" + launcherClass.getName();
		AbstractApplicationLauncher cachedLauncher = this.launcherCache.get(cacheKey);
		if (cachedLauncher != null) {
			return cachedLauncher;
		}
		try {
			Constructor<? extends AbstractApplicationLauncher> constructor = ReflectionUtils
				.accessibleConstructor(launcherClass, Application.class, File.class);
			AbstractApplicationLauncher launcher = BeanUtils.instantiateClass(constructor, application,
					new File(buildOutput.getRootLocation(), "app-launcher-" + UUID.randomUUID()));
			this.launcherCache.put(cacheKey, launcher);
			return launcher;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(String
				.format("Launcher class %s does not have an (Application, File) constructor", launcherClass.getName()));
		}
	}

	private Application getApplication(EmbeddedServletContainerTest annotation, String container) {
		return new Application(annotation.packaging(), container);
	}

	static class EmbeddedServletContainerInvocationContext implements TestTemplateInvocationContext, ParameterResolver {

		private final String name;

		private final AbstractApplicationLauncher launcher;

		EmbeddedServletContainerInvocationContext(String name, AbstractApplicationLauncher launcher) {
			this.name = name;
			this.launcher = launcher;
		}

		@Override
		public List<Extension> getAdditionalExtensions() {
			return Arrays.asList(this.launcher, new RestTemplateParameterResolver(this.launcher));
		}

		@Override
		public String getDisplayName(int invocationIndex) {
			return this.name;
		}

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			if (parameterContext.getParameter().getType().equals(AbstractApplicationLauncher.class)) {
				return true;
			}
			return parameterContext.getParameter().getType().equals(RestTemplate.class);
		}

		@Override
		public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			if (parameterContext.getParameter().getType().equals(AbstractApplicationLauncher.class)) {
				return this.launcher;
			}
			return null;
		}

	}

	private static final class RestTemplateParameterResolver implements ParameterResolver {

		private final AbstractApplicationLauncher launcher;

		private RestTemplateParameterResolver(AbstractApplicationLauncher launcher) {
			this.launcher = launcher;
		}

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return parameterContext.getParameter().getType().equals(RestTemplate.class);
		}

		@Override
		public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			RestTemplate rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(HttpClients.custom()
				.setRetryStrategy(new DefaultHttpRequestRetryStrategy(10, TimeValue.of(1, TimeUnit.SECONDS)))
				.build()));
			rest.setErrorHandler(new NoOpResponseErrorHandler());
			rest.setUriTemplateHandler(new UriTemplateHandler() {

				@Override
				public URI expand(String uriTemplate, Object... uriVariables) {
					return URI.create("http://localhost:" + RestTemplateParameterResolver.this.launcher.getHttpPort()
							+ uriTemplate);
				}

				@Override
				public URI expand(String uriTemplate, Map<String, ?> uriVariables) {
					return URI.create("http://localhost:" + RestTemplateParameterResolver.this.launcher.getHttpPort()
							+ uriTemplate);
				}

			});
			return rest;
		}

	}

}
