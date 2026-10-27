package com.xhh.lock.core;

import com.xhh.lock.config.LockProperties;
import com.xhh.lock.exception.LockKeyParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpEL 锁名解析的单元测试，不需要 Redis。
 */
class LockKeyParserTest {

    private final LockProperties properties = new LockProperties();
    private final LockKeyParser parser = new LockKeyParser(properties);

    private static Method method(String name) {
        for (Method m : Sample.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("方法不存在: " + name);
    }

    @Test
    @DisplayName("普通参数：自动拼接全局前缀")
    void shouldPrefixSimpleSpel() {
        String key = parser.parse("#orderId", method("byId"), new Object[]{42L});
        assertEquals("lock:42", key);
    }

    @Test
    @DisplayName("对象属性：支持 #order.id 这种链式访问")
    void shouldResolveNestedProperty() {
        String key = parser.parse("'order:' + #order.id", method("byOrder"), new Object[]{new Order(7L)});
        assertEquals("lock:order:7", key);
    }

    @Test
    @DisplayName("模板写法：order:#{#orderId}")
    void shouldResolveTemplate() {
        String key = parser.parse("order:#{#orderId}", method("byId"), new Object[]{42L});
        assertEquals("lock:order:42", key);
    }

    @Test
    @DisplayName("未指定 key：退化为 类名#方法名，避免所有方法共用一把锁")
    void shouldFallbackToMethodName() {
        String key = parser.parse("", method("noKey"), new Object[0]);
        assertEquals("lock:Sample#noKey", key);
    }

    @Test
    @DisplayName("前缀可配置，配置为空时不拼接前缀")
    void shouldRespectEmptyPrefix() {
        LockProperties custom = new LockProperties();
        custom.setKeyPrefix("");
        LockKeyParser customParser = new LockKeyParser(custom);

        assertEquals("42", customParser.parse("#orderId", method("byId"), new Object[]{42L}));

        LockProperties withPrefix = new LockProperties();
        withPrefix.setKeyPrefix("pet:lock:");
        assertEquals("pet:lock:42",
                new LockKeyParser(withPrefix).parse("#orderId", method("byId"), new Object[]{42L}));
    }

    @Test
    @DisplayName("解析结果为 null：抛 LockKeyParseException，不返回 null 去落 Redis")
    void shouldFailWhenResultIsNull() {
        LockKeyParseException e = assertThrows(LockKeyParseException.class,
                () -> parser.parse("#notExists", method("byId"), new Object[]{1L}));
        assertTrue(e.getMessage().contains("#notExists"));
    }

    @Test
    @DisplayName("表达式语法错误：包装成 LockKeyParseException")
    void shouldWrapSyntaxError() {
        assertThrows(LockKeyParseException.class,
                () -> parser.parse("'order:' + #", method("byId"), new Object[]{1L}));
    }

    @SuppressWarnings("unused")
    static class Sample {
        public void byId(Long orderId) {
        }

        public void byOrder(Order order) {
        }

        public void noKey() {
        }
    }

    static class Order {
        private final Long id;

        Order(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }
}
