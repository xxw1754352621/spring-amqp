/*
 * Copyright 2016-2019 the original author or authors.
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

package org.springframework.amqp.rabbit.log4j2;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AbstractManager;
import org.apache.logging.log4j.core.async.BlockingQueueFactory;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.util.Integers;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactoryConfigurationUtils;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.DeclareExchangeConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;
import org.springframework.amqp.utils.JavaUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.rabbitmq.client.ConnectionFactory;

/**
 * A Log4j 2 appender that publishes logging events to an AMQP Exchange.
 *
 * @author Gary Russell
 * @author Stephen Oakey
 * @author Artem Bilan
 * @author Dominique Villard
 * @author Nicolas Ristock
 * @author Eugene Gusev
 *
 * @since 1.6
 */
@Plugin(name = "RabbitMQ", category = "Core", elementType = "appender", printObject = true)
public class AmqpAppender extends AbstractAppender {

	/**
	 * Key name for the application id (if there is one set via the appender config) in the message properties.
	 */
	public static final String APPLICATION_ID = "applicationId";

	/**
	 * Key name for the logger category name in the message properties.
	 */
	public static final String CATEGORY_NAME = "categoryName";

	/**
	 * Key name for the logger level name in the message properties.
	 */
	public static final String CATEGORY_LEVEL = "level";

	/**
	 * Key name for the thread name in the message properties.
	 */
	public static final String THREAD_NAME = "thread";

	/**
	 * Tha manager.
	 */
	private final AmqpManager manager;

	/**
	 * The template.
	 */
	private final RabbitTemplate rabbitTemplate = new RabbitTemplate();

	/**
	 * Where LoggingEvents are queued to send.
	 */
	private final BlockingQueue<Event> events;

	/**
	 * Used to synchronize access to pattern layouts.
	 */
	private final Object layoutMutex = new Object();

	@SuppressWarnings("deprecation") // For backward compatibility
	public AmqpAppender(String name, Filter filter, Layout<? extends Serializable> layout, boolean ignoreExceptions,
			AmqpManager manager, BlockingQueue<Event> eventQueue) {

		super(name, filter, layout, ignoreExceptions);
		this.manager = manager;
		this.events = eventQueue;
	}

	@PluginFactory
	public static AmqpAppender createAppender(// NOSONAR NCSS line count
			@PluginConfiguration final Configuration configuration,
			@PluginAttribute("name") String name,
			@PluginElement("Layout") Layout<? extends Serializable> layout,
			@PluginElement("Filter") Filter filter,
			@PluginAttribute("ignoreExceptions") boolean ignoreExceptions,
			@PluginAttribute("uri") URI uri,
			@PluginAttribute("host") String host,
			@PluginAttribute("port") String port,
			@PluginAttribute("addresses") String addresses,
			@PluginAttribute("user") String user,
			@PluginAttribute("password") String password,
			@PluginAttribute("virtualHost") String virtualHost,
			@PluginAttribute("useSsl") boolean useSsl,
			@PluginAttribute("verifyHostname") boolean verifyHostname,
			@PluginAttribute("sslAlgorithm") String sslAlgorithm,
			@PluginAttribute("sslPropertiesLocation") String sslPropertiesLocation,
			@PluginAttribute("keyStore") String keyStore,
			@PluginAttribute("keyStorePassphrase") String keyStorePassphrase,
			@PluginAttribute("keyStoreType") String keyStoreType,
			@PluginAttribute("trustStore") String trustStore,
			@PluginAttribute("trustStorePassphrase") String trustStorePassphrase,
			@PluginAttribute("trustStoreType") String trustStoreType,
			@PluginAttribute("saslConfig") String saslConfig,
			@PluginAttribute("senderPoolSize") int senderPoolSize,
			@PluginAttribute("maxSenderRetries") int maxSenderRetries,
			@PluginAttribute("applicationId") String applicationId,
			@PluginAttribute("routingKeyPattern") String routingKeyPattern,
			@PluginAttribute("generateId") boolean generateId,
			@PluginAttribute("deliveryMode") String deliveryMode,
			@PluginAttribute("exchange") String exchange,
			@PluginAttribute("exchangeType") String exchangeType,
			@PluginAttribute("declareExchange") boolean declareExchange,
			@PluginAttribute("durable") boolean durable,
			@PluginAttribute("autoDelete") boolean autoDelete,
			@PluginAttribute("contentType") String contentType,
			@PluginAttribute("contentEncoding") String contentEncoding,
			@PluginAttribute("connectionName") String connectionName,
			@PluginAttribute("clientConnectionProperties") String clientConnectionProperties,
			@PluginAttribute("async") boolean async,
			@PluginAttribute("charset") String charset,
			@PluginAttribute(value = "bufferSize", defaultInt = Integer.MAX_VALUE) int bufferSize,
			@PluginElement(BlockingQueueFactory.ELEMENT_TYPE) BlockingQueueFactory<Event> blockingQueueFactory,
			@PluginAttribute(value = "addMdcAsHeaders", defaultBoolean = true) boolean addMdcAsHeaders) {

		if (name == null) {
			LOGGER.error("No name for AmqpAppender");
		}
		Layout<? extends Serializable> theLayout = layout;
		if (theLayout == null) {
			theLayout = PatternLayout.createDefaultLayout();
		}
		AmqpManager manager = new AmqpManager(configuration.getLoggerContext(), name);
		JavaUtils.INSTANCE
			.acceptIfNotNull(uri, value -> manager.uri = value)
			.acceptIfNotNull(host, value -> manager.host = value)
			.acceptIfNotNull(port, value -> manager.port = Integers.parseInt(value))
			.acceptIfNotNull(addresses, value -> manager.addresses = value)
			.acceptIfNotNull(user, value -> manager.username = value)
			.acceptIfNotNull(password, value -> manager.password = value)
			.acceptIfNotNull(virtualHost, value -> manager.virtualHost = value)
			.acceptIfNotNull(useSsl, value -> manager.useSsl = value)
			.acceptIfNotNull(verifyHostname, value -> manager.verifyHostname = value)
			.acceptIfNotNull(sslAlgorithm, value -> manager.sslAlgorithm = value)
			.acceptIfNotNull(sslPropertiesLocation, value -> manager.sslPropertiesLocation = value)
			.acceptIfNotNull(keyStore, value -> manager.keyStore = value)
			.acceptIfNotNull(keyStorePassphrase, value -> manager.keyStorePassphrase = value)
			.acceptIfNotNull(keyStoreType, value -> manager.keyStoreType = value)
			.acceptIfNotNull(trustStore, value -> manager.trustStore = value)
			.acceptIfNotNull(trustStorePassphrase, value -> manager.trustStorePassphrase = value)
			.acceptIfNotNull(trustStoreType, value -> manager.trustStoreType = value)
			.acceptIfNotNull(saslConfig, value -> manager.saslConfig = value)
			.acceptIfNotNull(senderPoolSize, value -> manager.senderPoolSize = value)
			.acceptIfNotNull(maxSenderRetries, value -> manager.maxSenderRetries = value)
			.acceptIfNotNull(applicationId, value -> manager.applicationId = value)
			.acceptIfNotNull(routingKeyPattern, value -> manager.routingKeyPattern = value)
			.acceptIfNotNull(generateId, value -> manager.generateId = value)
			.acceptIfNotNull(deliveryMode, value -> manager.deliveryMode = MessageDeliveryMode.valueOf(deliveryMode))
			.acceptIfNotNull(exchange, value -> manager.exchangeName = value)
			.acceptIfNotNull(exchangeType, value -> manager.exchangeType = value)
			.acceptIfNotNull(declareExchange, value -> manager.declareExchange = value)
			.acceptIfNotNull(durable, value -> manager.durable = value)
			.acceptIfNotNull(autoDelete, value -> manager.autoDelete = value)
			.acceptIfNotNull(contentType, value -> manager.contentType = value)
			.acceptIfNotNull(contentEncoding, value -> manager.contentEncoding = value)
			.acceptIfNotNull(connectionName, value -> manager.connectionName = value)
			.acceptIfNotNull(clientConnectionProperties, value -> manager.clientConnectionProperties = value)
			.acceptIfNotNull(charset, value -> manager.charset = value)
			.acceptIfNotNull(async, value -> manager.async = value)
			.acceptIfNotNull(addMdcAsHeaders, value -> manager.addMdcAsHeaders = value);

		BlockingQueue<Event> eventQueue;
		if (blockingQueueFactory == null) {
			eventQueue = new LinkedBlockingQueue<>(bufferSize);
		}
		else {
			eventQueue = blockingQueueFactory.create(bufferSize);
		}

		AmqpAppender appender = new AmqpAppender(name, filter, theLayout, ignoreExceptions, manager, eventQueue);
		if (manager.activateOptions()) {
			appender.startSenders();
			return appender;
		}
		return null;
	}

	/**
	 * Submit the required number of senders into the pool.
	 */
	private void startSenders() {
		this.rabbitTemplate.setConnectionFactory(this.manager.connectionFactory);
		if (this.manager.async) {
			for (int i = 0; i < this.manager.senderPoolSize; i++) {
				this.manager.senderPool.submit(new EventSender());
			}
		}
		else if (this.manager.maxSenderRetries > 0) {
			RetryTemplate retryTemplate = new RetryTemplate();
			RetryPolicy retryPolicy = new SimpleRetryPolicy(this.manager.maxSenderRetries);
			retryTemplate.setRetryPolicy(retryPolicy);
			this.rabbitTemplate.setRetryTemplate(retryTemplate);
		}
	}

	@Override
	public void append(LogEvent event) {
		Event appenderEvent = new Event(event, event.getContextData().toMap());
		if (this.manager.async) {
			this.events.add(appenderEvent);
		}
		else {
			sendEvent(appenderEvent, appenderEvent.getProperties());
		}
	}

	/**
	 * Subclasses may modify the final message before sending.
	 *
	 * @param message The message.
	 * @param event The event.
	 * @return The modified message.
	 */
	protected Message postProcessMessageBeforeSend(Message message, Event event) {
		return message;
	}

	protected void sendEvent(Event event, Map<?, ?> properties) {
		LogEvent logEvent = event.getEvent();
		String name = logEvent.getLoggerName();
		Level level = logEvent.getLevel();

		MessageProperties amqpProps = new MessageProperties();
		JavaUtils.INSTANCE
			.acceptIfNotNull(this.manager.deliveryMode, amqpProps::setDeliveryMode)
			.acceptIfNotNull(this.manager.contentType, amqpProps::setContentType)
			.acceptIfNotNull(this.manager.contentEncoding, amqpProps::setContentEncoding);
		amqpProps.setHeader(CATEGORY_NAME, name);
		amqpProps.setHeader(THREAD_NAME, logEvent.getThreadName());
		amqpProps.setHeader(CATEGORY_LEVEL, level.toString());
		if (this.manager.generateId) {
			amqpProps.setMessageId(UUID.randomUUID().toString());
		}

		// Set applicationId, if we're using one
		if (null != this.manager.applicationId) {
			amqpProps.setAppId(this.manager.applicationId);
		}

		// Set timestamp
		Calendar tstamp = Calendar.getInstance();
		tstamp.setTimeInMillis(logEvent.getTimeMillis());
		amqpProps.setTimestamp(tstamp.getTime());

		// Copy properties in from MDC
		if (this.manager.addMdcAsHeaders) {
			for (Entry<?, ?> entry : properties.entrySet()) {
				amqpProps.setHeader(entry.getKey().toString(), entry.getValue());
			}
		}
		if (logEvent.getSource() != null) {
			amqpProps.setHeader(
					"location",
					String.format("%s.%s()[%s]", logEvent.getSource().getClassName(),
							logEvent.getSource().getMethodName(),
							logEvent.getSource().getLineNumber()));
		}

		doSend(event, logEvent, amqpProps);
	}

	protected void doSend(Event event, LogEvent logEvent, MessageProperties amqpProps) {
		StringBuilder msgBody;
		String routingKey;
		try {
			synchronized (this.layoutMutex) {
				msgBody = new StringBuilder(new String(getLayout().toByteArray(logEvent), StandardCharsets.UTF_8));
				routingKey = new String(this.manager.routingKeyLayout.toByteArray(logEvent), StandardCharsets.UTF_8);
			}
			Message message = null;
			if (this.manager.charset != null) {
				try {
					message = new Message(msgBody.toString().getBytes(this.manager.charset),
							amqpProps);
				}
				catch (UnsupportedEncodingException e) {
					/* fall back to default */
				}
			}
			if (message == null) {
				message = new Message(msgBody.toString().getBytes(), amqpProps); //NOSONAR (default charset)
			}
			message = postProcessMessageBeforeSend(message, event);
			this.rabbitTemplate.send(this.manager.exchangeName, routingKey, message);
		}
		catch (AmqpException e) {
			int retries = event.incrementRetries();
			if (this.manager.async && retries < this.manager.maxSenderRetries) {
				// Schedule a retry based on the number of times I've tried to re-send this
				this.manager.retryTimer.schedule(new TimerTask() {

					@Override
					public void run() {
						AmqpAppender.this.events.add(event);
					}

				}, (long) (Math.pow(retries, Math.log(retries)) * 1000)); // NOSONAR - magic #
			}
			else {
				getHandler().error("Could not send log message " + logEvent.getMessage()
						+ " after " + this.manager.maxSenderRetries + " retries", logEvent, e);
			}
		}
		catch (Exception e) {
			getHandler().error("Could not send log message " + logEvent.getMessage(), logEvent, e);
		}
	}

	@Override
	protected boolean stop(long timeout, TimeUnit timeUnit, boolean changeLifeCycleState) {
		boolean stopped = super.stop(timeout, timeUnit, changeLifeCycleState);
		return stopped || this.manager.stop(timeout, timeUnit);
	}

	/**
	 * Return the number of events waiting to be sent.
	 * @return the number of events waiting to be sent.
	 */
	public int getQueuedEventCount() {
		return this.events.size();
	}

	/**
	 * Helper class to actually send LoggingEvents asynchronously.
	 */
	protected class EventSender implements Runnable {

		@Override
		public void run() {
			try {
				// We leave the loop when thread is interrupted by the ExecutorService.shutdownNow()
				while (true) {
					final Event event = AmqpAppender.this.events.take();
					sendEvent(event, event.getProperties());
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

	}

	/**
	 * Helper class to encapsulate a LoggingEvent, its MDC properties, and the number of retries.
	 */
	protected static class Event {

		private final LogEvent event;

		private final Map<?, ?> properties;

		private final AtomicInteger retries = new AtomicInteger(0);

		public Event(LogEvent event, Map<?, ?> properties) {
			this.event = event;
			this.properties = properties;
		}

		public LogEvent getEvent() {
			return this.event;
		}

		public Map<?, ?> getProperties() {
			return this.properties;
		}

		public int incrementRetries() {
			return this.retries.incrementAndGet();
		}

	}

	/**
	 * Manager class for the appender.
	 */
	protected static class AmqpManager extends AbstractManager {

		private static final int DEFAULT_MAX_SENDER_RETRIES = 30;

		/**
		 * True to send events on separate threads.
		 */
		private boolean async;

		/**
		 * Name of the exchange to publish log events to.
		 */
		private String exchangeName = "logs";

		/**
		 * Type of the exchange to publish log events to.
		 */
		private String exchangeType = "topic";

		/**
		 * Log4J pattern format to use to generate a routing key.
		 */
		private String routingKeyPattern = "%c.%p";

		/**
		 * Log4J Layout to use to generate routing key.
		 */
		private Layout<String> routingKeyLayout;

		/**
		 * Configuration arbitrary application ID.
		 */
		private String applicationId = null;

		/**
		 * How many senders to use at once. Use more senders if you have lots of log output going through this appender.
		 */
		private int senderPoolSize = 2;

		/**
		 * How many times to retry sending a message if the broker is unavailable or there is some other error.
		 */
		private int maxSenderRetries = DEFAULT_MAX_SENDER_RETRIES;

		/**
		 * RabbitMQ ConnectionFactory.
		 */
		private AbstractConnectionFactory connectionFactory;

		/**
		 * RabbitMQ host to connect to.
		 */
		private URI uri;

		/**
		 * RabbitMQ host to connect to.
		 */
		private String host;

		/**
		 * A comma-delimited list of broker addresses: host:port[,host:port]*.
		 */
		private String addresses;

		/**
		 * RabbitMQ virtual host to connect to.
		 */
		private String virtualHost;

		/**
		 * RabbitMQ port to connect to.
		 */
		private Integer port;

		/**
		 * RabbitMQ user to connect as.
		 */
		private String username;

		/**
		 * RabbitMQ password for this user.
		 */
		private String password;

		/**
		 * Use an SSL connection.
		 */
		private boolean useSsl;

		private boolean verifyHostname = true;

		/**
		 * The SSL algorithm to use.
		 */
		private String sslAlgorithm;

		/**
		 * Location of resource containing keystore and truststore information.
		 */
		private String sslPropertiesLocation;

		/**
		 * Keystore location.
		 */
		private String keyStore;

		/**
		 * Keystore passphrase.
		 */
		private String keyStorePassphrase;

		/**
		 * Keystore type.
		 */
		private String keyStoreType = "JKS";

		/**
		 * Truststore location.
		 */
		private String trustStore;

		/**
		 * Truststore passphrase.
		 */
		private String trustStorePassphrase;

		/**
		 * Truststore type.
		 */
		private String trustStoreType = "JKS";

		/**
		 * SaslConfig.
		 * @see RabbitUtils#stringToSaslConfig(String, ConnectionFactory)
		 */
		private String saslConfig;

		/**
		 * Default content-type of log messages.
		 */
		private String contentType = "text/plain";

		/**
		 * Default content-encoding of log messages.
		 */
		private String contentEncoding = null;

		/**
		 * Whether or not to try and declare the configured exchange when this appender starts.
		 */
		private boolean declareExchange = false;

		/**
		 * A name for the connection (appears on the RabbitMQ Admin UI).
		 */
		private String connectionName;

		/**
		 * Additional client connection properties to be added to the rabbit connection,
		 * with the form {@code key:value[,key:value]...}.
		 */
		private String clientConnectionProperties;

		/**
		 * charset to use when converting String to byte[], default null (system default charset used).
		 * If the charset is unsupported on the current platform, we fall back to using
		 * the system charset.
		 */
		private String charset = Charset.defaultCharset().name();

		/**
		 * Whether or not add MDC properties into message headers. true by default for backward compatibility
		 */
		private boolean addMdcAsHeaders = true;

		private boolean durable = true;

		private MessageDeliveryMode deliveryMode = MessageDeliveryMode.PERSISTENT;

		private boolean autoDelete = false;

		/**
		 * Used to determine whether {@link MessageProperties#setMessageId(String)} is set.
		 */
		private boolean generateId = false;

		/**
		 * The pool of senders.
		 */
		private ExecutorService senderPool = null;

		/**
		 * Retries are delayed like: N ^ log(N), where N is the retry number.
		 */
		private final Timer retryTimer = new Timer("log-event-retry-delay", true);

		protected AmqpManager(LoggerContext loggerContext, String name) {
			super(loggerContext, name);
		}

		private boolean activateOptions() {
			ConnectionFactory rabbitConnectionFactory = createRabbitConnectionFactory();
			if (rabbitConnectionFactory != null) {
				Assert.state(this.applicationId != null, "applicationId is required");
				this.routingKeyLayout = PatternLayout.newBuilder()
						.withPattern(this.routingKeyPattern.replaceAll("%X\\{applicationId}", this.applicationId))
						.withCharset(Charset.forName(this.charset))
						.withAlwaysWriteExceptions(false)
						.withNoConsoleNoAnsi(true)
						.build();
				this.connectionFactory = new CachingConnectionFactory(rabbitConnectionFactory);
				if (StringUtils.hasText(this.connectionName)) {
					this.connectionFactory.setConnectionNameStrategy(cf -> this.connectionName);
				}
				if (this.addresses != null) {
					this.connectionFactory.setAddresses(this.addresses);
				}
				if (this.clientConnectionProperties != null) {
					ConnectionFactoryConfigurationUtils.updateClientConnectionProperties(this.connectionFactory,
							this.clientConnectionProperties);
				}
				setUpExchangeDeclaration();
				this.senderPool = Executors.newCachedThreadPool();
				return true;
			}
			return false;
		}

		/**
		 * Create the {@link ConnectionFactory}.
		 *
		 * @return a {@link ConnectionFactory}.
		 */
		protected ConnectionFactory createRabbitConnectionFactory() {
			RabbitConnectionFactoryBean factoryBean = new RabbitConnectionFactoryBean();
			configureRabbitConnectionFactory(factoryBean);
			try {
				factoryBean.afterPropertiesSet();
				return factoryBean.getObject();
			}
			catch (Exception e) {
				LOGGER.error("Failed to create customized Rabbit ConnectionFactory.",
						e);
				return null;
			}
		}

		/**
		 * Configure the {@link RabbitConnectionFactoryBean}. Sub-classes may override to
		 * customize the configuration of the bean.
		 *
		 * @param factoryBean the {@link RabbitConnectionFactoryBean}.
		 */
		protected void configureRabbitConnectionFactory(RabbitConnectionFactoryBean factoryBean) {
			JavaUtils.INSTANCE
					.acceptIfNotNull(this.host, factoryBean::setHost)
					.acceptIfNotNull(this.port, factoryBean::setPort)
					.acceptIfNotNull(this.username, factoryBean::setUsername)
					.acceptIfNotNull(this.password, factoryBean::setPassword)
					.acceptIfNotNull(this.virtualHost, factoryBean::setVirtualHost)
					// overrides all preceding items when set
					.acceptIfNotNull(this.uri, factoryBean::setUri);

			if (this.useSsl) {
				factoryBean.setUseSSL(true);
				factoryBean.setEnableHostnameVerification(this.verifyHostname);
				if (this.sslAlgorithm != null) {
					factoryBean.setSslAlgorithm(this.sslAlgorithm);
				}
				if (this.sslPropertiesLocation != null) {
					PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
					Resource sslPropertiesResource = resolver.getResource(this.sslPropertiesLocation);
					factoryBean.setSslPropertiesLocation(sslPropertiesResource);
				}
				else {
					factoryBean.setKeyStore(this.keyStore);
					factoryBean.setKeyStorePassphrase(this.keyStorePassphrase);
					factoryBean.setKeyStoreType(this.keyStoreType);
					factoryBean.setTrustStore(this.trustStore);
					factoryBean.setTrustStorePassphrase(this.trustStorePassphrase);
					factoryBean.setTrustStoreType(this.trustStoreType);
					JavaUtils.INSTANCE
							.acceptIfNotNull(this.saslConfig, config -> {
								try {
									factoryBean.setSaslConfig(RabbitUtils.stringToSaslConfig(config,
											factoryBean.getRabbitConnectionFactory()));
								}
								catch (Exception e) {
									throw RabbitExceptionTranslator.convertRabbitAccessException(e);
								}
							});
				}
			}
		}

		@Override
		protected boolean releaseSub(long timeout, TimeUnit timeUnit) {
			this.retryTimer.cancel();
			this.senderPool.shutdownNow();
			this.connectionFactory.destroy();
			try {
				return this.senderPool.awaitTermination(timeout, timeUnit);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}

		protected void setUpExchangeDeclaration() {
			RabbitAdmin admin = new RabbitAdmin(this.connectionFactory);
			if (this.declareExchange) {
				Exchange x;
				if ("topic".equals(this.exchangeType)) {
					x = new TopicExchange(this.exchangeName, this.durable, this.autoDelete);
				}
				else if ("direct".equals(this.exchangeType)) {
					x = new DirectExchange(this.exchangeName, this.durable, this.autoDelete);
				}
				else if ("fanout".equals(this.exchangeType)) {
					x = new FanoutExchange(this.exchangeName, this.durable, this.autoDelete);
				}
				else if ("headers".equals(this.exchangeType)) {
					x = new HeadersExchange(this.exchangeName, this.durable, this.autoDelete);
				}
				else {
					x = new TopicExchange(this.exchangeName, this.durable, this.autoDelete);
				}
				this.connectionFactory.addConnectionListener(new DeclareExchangeConnectionListener(x, admin));
			}
		}

	}

}
