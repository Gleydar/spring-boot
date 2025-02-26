/*
 * Copyright 2012-2022 the original author or authors.
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

package org.springframework.boot.test.context;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.AotApplicationContextInitializer;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplication.AbandonedRunException;
import org.springframework.boot.SpringApplicationHook;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.test.context.SpringBootTest.UseMainMethod;
import org.springframework.boot.test.mock.web.SpringBootMockServletContext;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.util.TestPropertyValues.Type;
import org.springframework.boot.web.reactive.context.GenericReactiveWebApplicationContext;
import org.springframework.boot.web.servlet.support.ServletContextApplicationContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.SpringVersion;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextLoader;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.SmartContextLoader;
import org.springframework.test.context.aot.AotContextLoader;
import org.springframework.test.context.support.AbstractContextLoader;
import org.springframework.test.context.support.AnnotationConfigContextLoaderUtils;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.context.web.WebMergedContextConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.function.ThrowingSupplier;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * A {@link ContextLoader} that can be used to test Spring Boot applications (those that
 * normally startup using {@link SpringApplication}). Although this loader can be used
 * directly, most test will instead want to use it with
 * {@link SpringBootTest @SpringBootTest}.
 * <p>
 * The loader supports both standard {@link MergedContextConfiguration} as well as
 * {@link WebMergedContextConfiguration}. If {@link WebMergedContextConfiguration} is used
 * the context will either use a mock servlet environment, or start the full embedded web
 * server.
 * <p>
 * If {@code @ActiveProfiles} are provided in the test class they will be used to create
 * the application context.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @author Scott Frederick
 * @since 1.4.0
 * @see SpringBootTest
 */
public class SpringBootContextLoader extends AbstractContextLoader implements AotContextLoader {

	@Override
	public ApplicationContext loadContext(MergedContextConfiguration mergedConfig) throws Exception {
		return loadContext(mergedConfig, Mode.STANDARD, null);
	}

	@Override
	public ApplicationContext loadContextForAotProcessing(MergedContextConfiguration mergedConfig) throws Exception {
		return loadContext(mergedConfig, Mode.AOT_PROCESSING, null);
	}

	@Override
	public ApplicationContext loadContextForAotRuntime(MergedContextConfiguration mergedConfig,
			ApplicationContextInitializer<ConfigurableApplicationContext> initializer) throws Exception {
		return loadContext(mergedConfig, Mode.AOT_RUNTIME, initializer);
	}

	private ApplicationContext loadContext(MergedContextConfiguration mergedConfig, Mode mode,
			ApplicationContextInitializer<ConfigurableApplicationContext> initializer) {
		assertHasClassesOrLocations(mergedConfig);
		SpringBootTestAnnotation annotation = SpringBootTestAnnotation.get(mergedConfig);
		String[] args = annotation.getArgs();
		UseMainMethod useMainMethod = annotation.getUseMainMethod();
		ContextLoaderHook hook = new ContextLoaderHook(mergedConfig, mode, initializer);
		if (useMainMethod != UseMainMethod.NEVER) {
			Method mainMethod = getMainMethod(mergedConfig, useMainMethod);
			if (mainMethod != null) {
				return hook.run(() -> ReflectionUtils.invokeMethod(mainMethod, null, new Object[] { args }));
			}
		}
		SpringApplication application = getSpringApplication();
		return hook.run(() -> application.run(args));
	}

	private void assertHasClassesOrLocations(MergedContextConfiguration mergedConfig) {
		boolean hasClasses = !ObjectUtils.isEmpty(mergedConfig.getClasses());
		boolean hasLocations = !ObjectUtils.isEmpty(mergedConfig.getLocations());
		Assert.state(hasClasses || hasLocations,
				() -> "No configuration classes or locations found in @SpringApplicationConfiguration. "
						+ "For default configuration detection to work you need Spring 4.0.3 or better (found "
						+ SpringVersion.getVersion() + ").");
	}

	private Method getMainMethod(MergedContextConfiguration mergedConfig, UseMainMethod useMainMethod) {
		Class<?> springBootConfiguration = Arrays.stream(mergedConfig.getClasses())
				.filter(this::isSpringBootConfiguration).findFirst().orElse(null);
		Assert.state(springBootConfiguration != null || useMainMethod == UseMainMethod.WHEN_AVAILABLE,
				"Cannot use main method as no @SpringBootConfiguration-annotated class is available");
		Method mainMethod = (springBootConfiguration != null)
				? ReflectionUtils.findMethod(springBootConfiguration, "main", String[].class) : null;
		Assert.state(mainMethod != null || useMainMethod == UseMainMethod.WHEN_AVAILABLE,
				() -> "Main method not found on '%s'".formatted(springBootConfiguration.getName()));
		return mainMethod;
	}

	private boolean isSpringBootConfiguration(Class<?> candidate) {
		return MergedAnnotations.from(candidate, SearchStrategy.TYPE_HIERARCHY)
				.isPresent(SpringBootConfiguration.class);
	}

	private void configure(MergedContextConfiguration mergedConfig, SpringApplication application) {
		application.setMainApplicationClass(mergedConfig.getTestClass());
		application.addPrimarySources(Arrays.asList(mergedConfig.getClasses()));
		application.getSources().addAll(Arrays.asList(mergedConfig.getLocations()));
		List<ApplicationContextInitializer<?>> initializers = getInitializers(mergedConfig, application);
		if (mergedConfig instanceof WebMergedContextConfiguration) {
			application.setWebApplicationType(WebApplicationType.SERVLET);
			if (!isEmbeddedWebEnvironment(mergedConfig)) {
				new WebConfigurer().configure(mergedConfig, application, initializers);
			}
		}
		else if (mergedConfig instanceof ReactiveWebMergedContextConfiguration) {
			application.setWebApplicationType(WebApplicationType.REACTIVE);
		}
		else {
			application.setWebApplicationType(WebApplicationType.NONE);
		}
		application.setApplicationContextFactory(
				(webApplicationType) -> getApplicationContextFactory(mergedConfig, webApplicationType));
		application.setInitializers(initializers);
		ConfigurableEnvironment environment = getEnvironment();
		if (environment != null) {
			prepareEnvironment(mergedConfig, application, environment, false);
			application.setEnvironment(environment);
		}
		else {
			application.addListeners(new PrepareEnvironmentListener(mergedConfig));
		}
	}

	private ConfigurableApplicationContext getApplicationContextFactory(MergedContextConfiguration mergedConfig,
			WebApplicationType webApplicationType) {
		if (webApplicationType != WebApplicationType.NONE && !isEmbeddedWebEnvironment(mergedConfig)) {
			if (webApplicationType == WebApplicationType.REACTIVE) {
				return new GenericReactiveWebApplicationContext();
			}
			if (webApplicationType == WebApplicationType.SERVLET) {
				return new GenericWebApplicationContext();
			}
		}
		return ApplicationContextFactory.DEFAULT.create(webApplicationType);
	}

	private void prepareEnvironment(MergedContextConfiguration mergedConfig, SpringApplication application,
			ConfigurableEnvironment environment, boolean applicationEnvironment) {
		setActiveProfiles(environment, mergedConfig.getActiveProfiles(), applicationEnvironment);
		ResourceLoader resourceLoader = (application.getResourceLoader() != null) ? application.getResourceLoader()
				: new DefaultResourceLoader(null);
		TestPropertySourceUtils.addPropertiesFilesToEnvironment(environment, resourceLoader,
				mergedConfig.getPropertySourceLocations());
		TestPropertySourceUtils.addInlinedPropertiesToEnvironment(environment, getInlinedProperties(mergedConfig));
	}

	private void setActiveProfiles(ConfigurableEnvironment environment, String[] profiles,
			boolean applicationEnvironment) {
		if (ObjectUtils.isEmpty(profiles)) {
			return;
		}
		if (!applicationEnvironment) {
			environment.setActiveProfiles(profiles);
		}
		String[] pairs = new String[profiles.length];
		for (int i = 0; i < profiles.length; i++) {
			pairs[i] = "spring.profiles.active[" + i + "]=" + profiles[i];
		}
		TestPropertyValues.of(pairs).applyTo(environment, Type.MAP, "active-test-profiles");
	}

	/**
	 * Builds new {@link org.springframework.boot.SpringApplication} instance. This method
	 * is only called when a {@code main} method isn't being used to create the
	 * {@link SpringApplication}.
	 * @return a {@link SpringApplication} instance
	 */
	protected SpringApplication getSpringApplication() {
		return new SpringApplication();
	}

	/**
	 * Returns the {@link ConfigurableEnvironment} instance that should be applied to
	 * {@link SpringApplication} or {@code null} to use the default. You can override this
	 * method if you need a custom environment.
	 * @return a {@link ConfigurableEnvironment} instance
	 */
	protected ConfigurableEnvironment getEnvironment() {
		return null;
	}

	protected String[] getInlinedProperties(MergedContextConfiguration mergedConfig) {
		ArrayList<String> properties = new ArrayList<>();
		// JMX bean names will clash if the same bean is used in multiple contexts
		properties.add("spring.jmx.enabled=false");
		properties.addAll(Arrays.asList(mergedConfig.getPropertySourceProperties()));
		return StringUtils.toStringArray(properties);
	}

	/**
	 * Return the {@link ApplicationContextInitializer initializers} that will be applied
	 * to the context. By default this method will adapt {@link ContextCustomizer context
	 * customizers}, add {@link SpringApplication#getInitializers() application
	 * initializers} and add
	 * {@link MergedContextConfiguration#getContextInitializerClasses() initializers
	 * specified on the test}.
	 * @param mergedConfig the source context configuration
	 * @param application the application instance
	 * @return the initializers to apply
	 * @since 2.0.0
	 */
	protected List<ApplicationContextInitializer<?>> getInitializers(MergedContextConfiguration mergedConfig,
			SpringApplication application) {
		List<ApplicationContextInitializer<?>> initializers = new ArrayList<>();
		for (ContextCustomizer contextCustomizer : mergedConfig.getContextCustomizers()) {
			initializers.add(new ContextCustomizerAdapter(contextCustomizer, mergedConfig));
		}
		initializers.addAll(application.getInitializers());
		for (Class<? extends ApplicationContextInitializer<?>> initializerClass : mergedConfig
				.getContextInitializerClasses()) {
			initializers.add(BeanUtils.instantiateClass(initializerClass));
		}
		if (mergedConfig.getParent() != null) {
			ApplicationContext parentApplicationContext = mergedConfig.getParentApplicationContext();
			initializers.add(new ParentContextApplicationContextInitializer(parentApplicationContext));
		}
		return initializers;
	}

	private boolean isEmbeddedWebEnvironment(MergedContextConfiguration mergedConfig) {
		return SpringBootTestAnnotation.get(mergedConfig).getWebEnvironment().isEmbedded();
	}

	@Override
	public void processContextConfiguration(ContextConfigurationAttributes configAttributes) {
		super.processContextConfiguration(configAttributes);
		if (!configAttributes.hasResources()) {
			Class<?>[] defaultConfigClasses = detectDefaultConfigurationClasses(configAttributes.getDeclaringClass());
			configAttributes.setClasses(defaultConfigClasses);
		}
	}

	/**
	 * Detect the default configuration classes for the supplied test class. By default
	 * simply delegates to
	 * {@link AnnotationConfigContextLoaderUtils#detectDefaultConfigurationClasses}.
	 * @param declaringClass the test class that declared {@code @ContextConfiguration}
	 * @return an array of default configuration classes, potentially empty but never
	 * {@code null}
	 * @see AnnotationConfigContextLoaderUtils
	 */
	protected Class<?>[] detectDefaultConfigurationClasses(Class<?> declaringClass) {
		return AnnotationConfigContextLoaderUtils.detectDefaultConfigurationClasses(declaringClass);
	}

	@Override
	protected String[] getResourceSuffixes() {
		return new String[] { "-context.xml", "Context.groovy" };
	}

	@Override
	protected String getResourceSuffix() {
		throw new IllegalStateException();
	}

	/**
	 * Modes that the {@link SpringBootContextLoader} can operate.
	 */
	private enum Mode {

		/**
		 * Load for regular usage.
		 * @see SmartContextLoader#loadContext
		 */
		STANDARD,

		/**
		 * Load for AOT processing.
		 * @see AotContextLoader#loadContextForAotProcessing
		 */
		AOT_PROCESSING,

		/**
		 * Load for AOT runtime.
		 * @see AotContextLoader#loadContextForAotRuntime
		 */
		AOT_RUNTIME

	}

	/**
	 * Inner class to configure {@link WebMergedContextConfiguration}.
	 */
	private static class WebConfigurer {

		void configure(MergedContextConfiguration mergedConfig, SpringApplication application,
				List<ApplicationContextInitializer<?>> initializers) {
			WebMergedContextConfiguration webMergedConfig = (WebMergedContextConfiguration) mergedConfig;
			addMockServletContext(initializers, webMergedConfig);
		}

		private void addMockServletContext(List<ApplicationContextInitializer<?>> initializers,
				WebMergedContextConfiguration webMergedConfig) {
			SpringBootMockServletContext servletContext = new SpringBootMockServletContext(
					webMergedConfig.getResourceBasePath());
			initializers.add(0, new DefensiveWebApplicationContextInitializer(
					new ServletContextApplicationContextInitializer(servletContext, true)));
		}

		/**
		 * Decorator for {@link ServletContextApplicationContextInitializer} that prevents
		 * a failure when the context type is not as was predicted when the initializer
		 * was registered. This can occur when spring.main.web-application-type is set to
		 * something other than servlet.
		 */
		private static final class DefensiveWebApplicationContextInitializer
				implements ApplicationContextInitializer<ConfigurableApplicationContext> {

			private final ServletContextApplicationContextInitializer delegate;

			private DefensiveWebApplicationContextInitializer(ServletContextApplicationContextInitializer delegate) {
				this.delegate = delegate;
			}

			@Override
			public void initialize(ConfigurableApplicationContext applicationContext) {
				if (applicationContext instanceof ConfigurableWebApplicationContext webApplicationContext) {
					this.delegate.initialize(webApplicationContext);
				}
			}

		}

	}

	/**
	 * Adapts a {@link ContextCustomizer} to a {@link ApplicationContextInitializer} so
	 * that it can be triggered via {@link SpringApplication}.
	 */
	private static class ContextCustomizerAdapter
			implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		private final ContextCustomizer contextCustomizer;

		private final MergedContextConfiguration mergedConfig;

		ContextCustomizerAdapter(ContextCustomizer contextCustomizer, MergedContextConfiguration mergedConfig) {
			this.contextCustomizer = contextCustomizer;
			this.mergedConfig = mergedConfig;
		}

		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			this.contextCustomizer.customizeContext(applicationContext, this.mergedConfig);
		}

	}

	/**
	 * {@link ApplicationContextInitializer} used to set the parent context.
	 */
	@Order(Ordered.HIGHEST_PRECEDENCE)
	private static class ParentContextApplicationContextInitializer
			implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		private final ApplicationContext parent;

		ParentContextApplicationContextInitializer(ApplicationContext parent) {
			this.parent = parent;
		}

		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			applicationContext.setParent(this.parent);
		}

	}

	/**
	 * {@link ApplicationListener} used to prepare the application created environment.
	 */
	private class PrepareEnvironmentListener
			implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, PriorityOrdered {

		private final MergedContextConfiguration mergedConfig;

		PrepareEnvironmentListener(MergedContextConfiguration mergedConfig) {
			this.mergedConfig = mergedConfig;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			prepareEnvironment(this.mergedConfig, event.getSpringApplication(), event.getEnvironment(), true);
		}

	}

	/**
	 * {@link SpringApplicationHook} used to capture the {@link ApplicationContext} and to
	 * trigger early exit for the {@link Mode#AOT_PROCESSING} mode.
	 */
	private class ContextLoaderHook implements SpringApplicationHook {

		private final MergedContextConfiguration mergedConfig;

		private final Mode mode;

		private final ApplicationContextInitializer<ConfigurableApplicationContext> initializer;

		ContextLoaderHook(MergedContextConfiguration mergedConfig, Mode mode,
				ApplicationContextInitializer<ConfigurableApplicationContext> initializer) {
			this.mergedConfig = mergedConfig;
			this.mode = mode;
			this.initializer = initializer;
		}

		@Override
		public SpringApplicationRunListener getRunListener(SpringApplication application) {
			return new SpringApplicationRunListener() {

				@Override
				public void starting(ConfigurableBootstrapContext bootstrapContext) {
					SpringBootContextLoader.this.configure(ContextLoaderHook.this.mergedConfig, application);
					if (ContextLoaderHook.this.initializer != null) {
						application.addInitializers(
								AotApplicationContextInitializer.of(ContextLoaderHook.this.initializer));
					}
				}

				@Override
				public void contextLoaded(ConfigurableApplicationContext context) {
					if (ContextLoaderHook.this.mode == Mode.AOT_PROCESSING) {
						throw new AbandonedRunException(context);
					}
				}

				@Override
				public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
					throw new AbandonedRunException(context);
				}

			};
		}

		private <T> ApplicationContext run(ThrowingSupplier<T> action) {
			try {
				SpringApplication.withHook(this, action);
				throw new IllegalStateException("ApplicationContext not loaded");
			}
			catch (AbandonedRunException ex) {
				return ex.getApplicationContext();
			}
		}

	}

}
