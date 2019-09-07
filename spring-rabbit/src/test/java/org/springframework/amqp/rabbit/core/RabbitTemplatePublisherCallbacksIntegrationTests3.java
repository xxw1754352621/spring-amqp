/*
 * Copyright 2018-2019 the original author or authors.
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

package org.springframework.amqp.rabbit.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.junit.RabbitAvailable;
import org.springframework.amqp.rabbit.junit.RabbitAvailableCondition;
import org.springframework.amqp.utils.test.TestUtils;

import com.rabbitmq.client.Channel;

/**
 * @author Gary Russell
 * @since 2.1
 *
 */
@RabbitAvailable(queues = { RabbitTemplatePublisherCallbacksIntegrationTests3.QUEUE1,
		RabbitTemplatePublisherCallbacksIntegrationTests3.QUEUE2,
		RabbitTemplatePublisherCallbacksIntegrationTests3.QUEUE3 })
public class RabbitTemplatePublisherCallbacksIntegrationTests3 {

	public static final String QUEUE1 = "synthetic.nack";

	public static final String QUEUE2 = "defer.close";

	public static final String QUEUE3 = "confirm.send.receive";

	@Test
	public void testRepublishOnNackThreadNoExchange() throws Exception {
		CachingConnectionFactory cf = new CachingConnectionFactory(
				RabbitAvailableCondition.getBrokerRunning().getConnectionFactory());
		cf.setPublisherConfirmType(ConfirmType.CORRELATED);
		final RabbitTemplate template = new RabbitTemplate(cf);
		final CountDownLatch confirmLatch = new CountDownLatch(2);
		template.setConfirmCallback((cd, a, c) -> {
			if (confirmLatch.getCount() == 2) {
				template.convertAndSend(QUEUE1, ((MyCD) cd).payload);
			}
			confirmLatch.countDown();
		});
		template.convertAndSend("bad.exchange", "junk", "foo", new MyCD("foo"));
		assertThat(confirmLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(template.receive(QUEUE1, 10_000)).isNotNull();
	}

	@Test
	public void testDeferredChannelCacheNack() throws Exception {
		final CachingConnectionFactory cf = new CachingConnectionFactory(
				RabbitAvailableCondition.getBrokerRunning().getConnectionFactory());
		cf.setPublisherReturns(true);
		cf.setPublisherConfirmType(ConfirmType.CORRELATED);
		final RabbitTemplate template = new RabbitTemplate(cf);
		final CountDownLatch returnLatch = new CountDownLatch(1);
		final CountDownLatch confirmLatch = new CountDownLatch(1);
		final AtomicInteger cacheCount = new AtomicInteger();
		final AtomicBoolean returnCalledFirst = new AtomicBoolean();
		template.setConfirmCallback((cd, a, c) -> {
			cacheCount.set(TestUtils.getPropertyValue(cf, "cachedChannelsNonTransactional", List.class).size());
			returnCalledFirst.set(returnLatch.getCount() == 0);
			confirmLatch.countDown();
		});
		template.setReturnCallback((m, r, rt, e, rk) -> {
			returnLatch.countDown();
		});
		template.setMandatory(true);
		Connection conn = cf.createConnection();
		Channel channel1 = conn.createChannel(false);
		Channel channel2 = conn.createChannel(false);
		channel1.close();
		channel2.close();
		conn.close();
		assertThat(TestUtils.getPropertyValue(cf, "cachedChannelsNonTransactional", List.class).size()).isEqualTo(2);
		template.convertAndSend("", QUEUE2 + "junk", "foo", new MyCD("foo"));
		assertThat(returnLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(confirmLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(cacheCount.get()).isEqualTo(1);
		assertThat(returnCalledFirst.get()).isTrue();
		cf.destroy();
	}

	@Test
	public void testDeferredChannelCacheAck() throws Exception {
		final CachingConnectionFactory cf = new CachingConnectionFactory(
				RabbitAvailableCondition.getBrokerRunning().getConnectionFactory());
		cf.setPublisherConfirmType(ConfirmType.CORRELATED);
		final RabbitTemplate template = new RabbitTemplate(cf);
		final CountDownLatch confirmLatch = new CountDownLatch(1);
		final AtomicInteger cacheCount = new AtomicInteger();
		template.setConfirmCallback((cd, a, c) -> {
			cacheCount.set(TestUtils.getPropertyValue(cf, "cachedChannelsNonTransactional", List.class).size());
			confirmLatch.countDown();
		});
		template.setMandatory(true);
		Connection conn = cf.createConnection();
		Channel channel1 = conn.createChannel(false);
		Channel channel2 = conn.createChannel(false);
		channel1.close();
		channel2.close();
		conn.close();
		assertThat(TestUtils.getPropertyValue(cf, "cachedChannelsNonTransactional", List.class).size()).isEqualTo(2);
		template.convertAndSend("", QUEUE2, "foo", new MyCD("foo"));
		assertThat(confirmLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(cacheCount.get()).isEqualTo(1);
		cf.destroy();
	}

	@Test
	public void testTwoSendsAndReceivesDRTMLC() throws Exception {
		CachingConnectionFactory cf = new CachingConnectionFactory(
				RabbitAvailableCondition.getBrokerRunning().getConnectionFactory());
		cf.setPublisherConfirmType(ConfirmType.CORRELATED);
		RabbitTemplate template = new RabbitTemplate(cf);
		template.setReplyTimeout(0);
		final CountDownLatch confirmLatch = new CountDownLatch(2);
		template.setConfirmCallback((cd, a, c) -> {
			confirmLatch.countDown();
		});
		template.convertSendAndReceive("", QUEUE3, "foo", new MyCD("foo"));
		template.convertSendAndReceive("", QUEUE3, "foo", new MyCD("foo")); // listener not registered
		assertThat(confirmLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(template.receive(QUEUE3, 10_000)).isNotNull();
		assertThat(template.receive(QUEUE3, 10_000)).isNotNull();
	}


	private static class MyCD extends CorrelationData {

		final String payload;

		MyCD(String payload) {
			this.payload = payload;
		}

	}

}
