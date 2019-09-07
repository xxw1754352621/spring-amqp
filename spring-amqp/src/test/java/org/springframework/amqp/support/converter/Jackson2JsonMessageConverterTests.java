/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.amqp.support.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.web.JsonPath;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.BeanSerializerFactory;

/**
 * @author Mark Pollack
 * @author Dave Syer
 * @author Sam Nelson
 * @author Gary Russell
 * @author Andreas Asplund
 * @author Artem Bilan
 */
@SpringJUnitConfig
public class Jackson2JsonMessageConverterTests {

	public static final String TRUSTED_PACKAGE = Jackson2JsonMessageConverterTests.class.getPackage().getName();

	private Jackson2JsonMessageConverter converter;

	private SimpleTrade trade;

	@Autowired
	private Jackson2JsonMessageConverter jsonConverterWithDefaultType;

	@BeforeEach
	public void before() {
		converter = new Jackson2JsonMessageConverter(TRUSTED_PACKAGE);
		trade = new SimpleTrade();
		trade.setAccountName("Acct1");
		trade.setBuyRequest(true);
		trade.setOrderType("Market");
		trade.setPrice(new BigDecimal(103.30));
		trade.setQuantity(100);
		trade.setRequestId("R123");
		trade.setTicker("VMW");
		trade.setUserName("Joe Trader");
	}

	@Test
	public void simpleTrade() {
		Message message = converter.toMessage(trade, new MessageProperties());

		SimpleTrade marshalledTrade = (SimpleTrade) converter.fromMessage(message);
		assertThat(marshalledTrade).isEqualTo(trade);
	}

	@Test
	public void simpleTradeOverrideMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializerFactory(BeanSerializerFactory.instance);
		converter = new Jackson2JsonMessageConverter(mapper);

		((DefaultJackson2JavaTypeMapper) this.converter.getJavaTypeMapper())
				.setTrustedPackages(TRUSTED_PACKAGE);

		Message message = converter.toMessage(trade, new MessageProperties());

		SimpleTrade marshalledTrade = (SimpleTrade) converter.fromMessage(message);
		assertThat(marshalledTrade).isEqualTo(trade);
	}

	@Test
	public void nestedBean() {
		Bar bar = new Bar();
		bar.getFoo().setName("spam");

		Message message = converter.toMessage(bar, new MessageProperties());

		Bar marshalled = (Bar) converter.fromMessage(message);
		assertThat(marshalled).isEqualTo(bar);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void hashtable() {
		Hashtable<String, String> hashtable = new Hashtable<String, String>();
		hashtable.put("TICKER", "VMW");
		hashtable.put("PRICE", "103.2");

		Message message = converter.toMessage(hashtable, new MessageProperties());
		Hashtable<String, String> marhsalledHashtable = (Hashtable<String, String>) converter.fromMessage(message);

		assertThat(marhsalledHashtable.get("TICKER")).isEqualTo("VMW");
		assertThat(marhsalledHashtable.get("PRICE")).isEqualTo("103.2");
	}

	@Test
	public void shouldUseClassMapperWhenProvided() {
		Message message = converter.toMessage(trade, new MessageProperties());

		converter.setClassMapper(new DefaultClassMapper());

		((DefaultClassMapper) this.converter.getClassMapper()).setTrustedPackages(TRUSTED_PACKAGE);

		SimpleTrade marshalledTrade = (SimpleTrade) converter.fromMessage(message);
		assertThat(marshalledTrade).isEqualTo(trade);
	}

	@Test
	public void shouldUseClassMapperWhenProvidedOutbound() {
		DefaultClassMapper classMapper = new DefaultClassMapper();
		classMapper.setTrustedPackages(TRUSTED_PACKAGE);

		converter.setClassMapper(classMapper);
		Message message = converter.toMessage(trade, new MessageProperties());

		SimpleTrade marshalledTrade = (SimpleTrade) converter.fromMessage(message);
		assertThat(marshalledTrade).isEqualTo(trade);
	}

	@Test
	public void testAmqp330StringArray() {
		String[] testData = { "test" };
		Message message = converter.toMessage(testData, new MessageProperties());
		assertThat((Object[]) converter.fromMessage(message)).isEqualTo(testData);
	}

	@Test
	public void testAmqp330ObjectArray() {
		SimpleTrade[] testData = { trade };
		Message message = converter.toMessage(testData, new MessageProperties());
		assertThat((Object[]) converter.fromMessage(message)).isEqualTo(testData);
	}

	@Test
	public void testDefaultType() {
		byte[] bytes = "{\"name\" : \"foo\" }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		Message message = new Message(bytes, messageProperties);
		Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
		DefaultClassMapper classMapper = new DefaultClassMapper();
		classMapper.setDefaultType(Foo.class);
		converter.setClassMapper(classMapper);
		Object foo = converter.fromMessage(message);
		assertThat(foo instanceof Foo).isTrue();
	}

	@Test
	public void testDefaultTypeConfig() {
		byte[] bytes = "{\"name\" : \"foo\" }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		Message message = new Message(bytes, messageProperties);
		Object foo = jsonConverterWithDefaultType.fromMessage(message);
		assertThat(foo instanceof Foo).isTrue();
	}

	@Test
	public void testNoJsonContentType() {
		byte[] bytes = "{\"name\" : \"foo\" }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		Message message = new Message(bytes, messageProperties);
		this.jsonConverterWithDefaultType.setAssumeSupportedContentType(false);
		Object foo = this.jsonConverterWithDefaultType.fromMessage(message);
		assertThat(foo).isEqualTo(bytes);
	}

	@Test
	public void testNoTypeInfo() {
		byte[] bytes = "{\"name\" : { \"foo\" : \"bar\" } }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		Message message = new Message(bytes, messageProperties);
		Object foo = this.converter.fromMessage(message);
		assertThat(foo).isInstanceOf(LinkedHashMap.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> map = (Map<String, Object>) foo;
		assertThat(map.get("name")).isInstanceOf(LinkedHashMap.class);
	}

	@Test
	public void testInferredTypeInfo() {
		byte[] bytes = "{\"name\" : \"foo\" }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		messageProperties.setInferredArgumentType(Foo.class);
		Message message = new Message(bytes, messageProperties);
		Object foo = this.converter.fromMessage(message);
		assertThat(foo).isInstanceOf(Foo.class);
	}

	@Test
	public void testInferredGenericTypeInfo() throws Exception {
		byte[] bytes = "[ {\"name\" : \"foo\" } ]".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		messageProperties.setInferredArgumentType((new ParameterizedTypeReference<List<Foo>>() { }).getType());
		Message message = new Message(bytes, messageProperties);
		Object foo = this.converter.fromMessage(message);
		assertThat(foo).isInstanceOf(List.class);
		assertThat(((List<?>) foo).get(0)).isInstanceOf(Foo.class);
	}

	@Test
	public void testInferredGenericMap1() {
		byte[] bytes = "{\"qux\" : [ { \"foo\" : { \"name\" : \"bar\" } } ] }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		messageProperties.setInferredArgumentType(
				(new ParameterizedTypeReference<Map<String, List<Bar>>>() {	}).getType());
		Message message = new Message(bytes, messageProperties);
		Object foo = this.converter.fromMessage(message);
		assertThat(foo).isInstanceOf(LinkedHashMap.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> map = (Map<String, Object>) foo;
		assertThat(map.get("qux")).isInstanceOf(List.class);
		Object row = ((List<?>) map.get("qux")).get(0);
		assertThat(row).isInstanceOf(Bar.class);
		assertThat(((Bar) row).getFoo()).isEqualTo(new Foo("bar"));
	}

	@Test
	public void testInferredGenericMap2() {
		byte[] bytes = "{\"qux\" : { \"baz\" : { \"foo\" : { \"name\" : \"bar\" } } } }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType("application/json");
		messageProperties.setInferredArgumentType(
				(new ParameterizedTypeReference<Map<String, Map<String, Bar>>>() { }).getType());
		Message message = new Message(bytes, messageProperties);
		Object foo = this.converter.fromMessage(message);
		assertThat(foo).isInstanceOf(LinkedHashMap.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> map = (Map<String, Object>) foo;
		assertThat(map.get("qux")).isInstanceOf(Map.class);
		Object value = ((Map<?, ?>) map.get("qux")).get("baz");
		assertThat(value).isInstanceOf(Bar.class);
		assertThat(((Bar) value).getFoo()).isEqualTo(new Foo("bar"));
	}

	@Test
	public void testProjection() {
		Jackson2JsonMessageConverter conv = new Jackson2JsonMessageConverter();
		conv.setUseProjectionForInterfaces(true);
		MessageProperties properties = new MessageProperties();
		properties.setInferredArgumentType(Sample.class);
		properties.setContentType("application/json");
		Message message = new Message(
				"{ \"username\" : \"SomeUsername\", \"user\" : { \"name\" : \"SomeName\"}}".getBytes(), properties);
		Object fromMessage = conv.fromMessage(message);
		assertThat(fromMessage).isInstanceOf(Sample.class);
		assertThat(((Sample) fromMessage).getUsername()).isEqualTo("SomeUsername");
		assertThat(((Sample) fromMessage).getName()).isEqualTo("SomeName");
	}

	@Test
	public void testMissingContentType() {
		byte[] bytes = "{\"name\" : \"foo\" }".getBytes();
		MessageProperties messageProperties = new MessageProperties();
		Message message = new Message(bytes, messageProperties);
		Jackson2JsonMessageConverter j2Converter = new Jackson2JsonMessageConverter();
		DefaultClassMapper classMapper = new DefaultClassMapper();
		classMapper.setDefaultType(Foo.class);
		j2Converter.setClassMapper(classMapper);
		Object foo = j2Converter.fromMessage(message);
		assertThat(foo).isInstanceOf(Foo.class);

		messageProperties.setContentType(null);
		foo = j2Converter.fromMessage(message);
		assertThat(foo).isInstanceOf(Foo.class);

		j2Converter.setAssumeSupportedContentType(false);
		foo = j2Converter.fromMessage(message);
		assertThat(foo).isSameAs(bytes);
	}

	public static class Foo {

		private String name = "foo";

		public Foo() {
		}

		public Foo(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			Foo other = (Foo) obj;
			if (name == null) {
				if (other.name != null) {
					return false;
				}
			}
			else if (!name.equals(other.name)) {
				return false;
			}
			return true;
		}

	}

	public static class Bar {

		private String name = "bar";

		private Foo foo = new Foo();

		public Foo getFoo() {
			return foo;
		}

		public void setFoo(Foo foo) {
			this.foo = foo;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((foo == null) ? 0 : foo.hashCode());
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			Bar other = (Bar) obj;
			if (foo == null) {
				if (other.foo != null) {
					return false;
				}
			}
			else if (!foo.equals(other.foo)) {
				return false;
			}
			if (name == null) {
				if (other.name != null) {
					return false;
				}
			}
			else if (!name.equals(other.name)) {
				return false;
			}
			return true;
		}

	}

	interface Sample {

		String getUsername();

		@JsonPath("$.user.name")
		String getName();

	}

}
