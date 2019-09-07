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

package org.springframework.amqp.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * @author Gary Russell
 * @since 1.6
 *
 */
public class BuilderTests {

	@Test
	public void testQueueBuilder() {
		Queue queue = QueueBuilder.durable("foo").autoDelete().exclusive().withArgument("foo", "bar").build();
		assertThat(queue.getName()).isEqualTo("foo");
		assertThat(queue.isAutoDelete()).isTrue();
		assertThat(queue.isExclusive()).isTrue();
		assertThat(queue.isDurable()).isTrue();
		assertThat((String) queue.getArguments().get("foo")).isEqualTo("bar");

		queue = QueueBuilder.nonDurable().build();
		assertThat(queue.getName()).startsWith("spring.gen-");
		assertThat(queue.isAutoDelete()).isFalse();
		assertThat(queue.isExclusive()).isFalse();
		assertThat(queue.isDurable()).isFalse();

		queue = QueueBuilder.durable().build();
		assertThat(queue.getName()).startsWith("spring.gen-");
		assertThat(queue.isAutoDelete()).isFalse();
		assertThat(queue.isExclusive()).isFalse();
		assertThat(queue.isDurable()).isTrue();
	}

	@Test
	public void testExchangeBuilder() {
		Exchange exchange = ExchangeBuilder.directExchange("foo").autoDelete().delayed().internal()
				.withArgument("foo", "bar").build();
		assertThat(exchange).isInstanceOf(DirectExchange.class);
		assertThat(exchange.isAutoDelete()).isTrue();
		assertThat(exchange.isDurable()).isTrue();
		assertThat(exchange.isInternal()).isTrue();
		assertThat(exchange.isDelayed()).isTrue();
		assertThat((String) exchange.getArguments().get("foo")).isEqualTo("bar");

		exchange = ExchangeBuilder.topicExchange("foo").durable(false).build();
		assertThat(exchange).isInstanceOf(TopicExchange.class);
		assertThat(exchange.isAutoDelete()).isFalse();
		assertThat(exchange.isDurable()).isFalse();
		assertThat(exchange.isInternal()).isFalse();
		assertThat(exchange.isDelayed()).isFalse();

		exchange = ExchangeBuilder.fanoutExchange("foo").build();
		assertThat(exchange).isInstanceOf(FanoutExchange.class);
		assertThat(exchange.isAutoDelete()).isFalse();
		assertThat(exchange.isDurable()).isTrue();
		assertThat(exchange.isInternal()).isFalse();
		assertThat(exchange.isDelayed()).isFalse();

		exchange = ExchangeBuilder.headersExchange("foo").build();
		assertThat(exchange).isInstanceOf(HeadersExchange.class);
		assertThat(exchange.isAutoDelete()).isFalse();
		assertThat(exchange.isDurable()).isTrue();
		assertThat(exchange.isInternal()).isFalse();
		assertThat(exchange.isDelayed()).isFalse();
	}

}
