package com.xhh.lock.core;

import com.xhh.lock.config.LockProperties;
import com.xhh.lock.exception.LockKeyParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    @DisplayName("对象直接当 key：拒绝，并提示改用 #order.id")
    void shouldRejectObjectAsKey() {
        LockKeyParseException e = assertThrows(LockKeyParseException.class,
                () -> parser.parse("#order", method("byOrder"), new Object[]{new Order(7L)}));

        String message = e.getMessage();
        assertTrue(message.contains("#order.id"), message);
        assertTrue(message.contains("Order"), message);
        assertTrue(message.contains("hashCode"), message);
    }

    @Test
    @DisplayName("模板里放对象：同样拒绝，不能靠拼接把类型信息糊过去")
    void shouldRejectObjectInsideTemplate() {
        LockKeyParseException e = assertThrows(LockKeyParseException.class,
                () -> parser.parse("order:#{#order}", method("byOrder"), new Object[]{new Order(7L)}));

        assertTrue(e.getMessage().contains("#order.id"), e.getMessage());
    }

    @Test
    @DisplayName("重写了 toString 的值对象：放行，key 稳定")
    void shouldAllowValueObjectWithStableToString() {
        assertEquals("lock:vo-9",
                parser.parse("#vo", method("byValue"), new Object[]{new ValueObject(9L)}));
    }

    @Test
    @DisplayName("时间类型与枚举：本身就是标量语义，可以直接当 key")
    void shouldAllowTemporalAndEnum() {
        assertEquals("lock:2026-11-02",
                parser.parse("#date", method("byDate"), new Object[]{LocalDate.of(2026, 11, 2)}));
        assertEquals("lock:WRITE",
                parser.parse("#mode", method("byMode"), new Object[]{Mode.WRITE}));
    }

    @Test
    @DisplayName("没重写 toString 的类，toString 里确实带 hashCode —— 这就是必须拦的原因")
    void shouldDemonstrateWhyObjectKeyIsUnstable() {
        Order a = new Order(7L);
        Order b = new Order(7L);

        assertEquals(a.getId(), b.getId());
        assertNotEquals(String.valueOf(a), String.valueOf(b));
    }

    @SuppressWarnings("unused")
    static class Sample {
        public void byId(Long orderId) {
        }

        public void byOrder(Order order) {
        }

        public void byValue(ValueObject vo) {
        }

        public void byDate(LocalDate date) {
        }

        public void byMode(Mode mode) {
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

    /** 重写了 toString 的值对象，等价于 Lombok @Data 生成的那种 */
    static class ValueObject {
        private final Long id;

        ValueObject(Long id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "vo-" + id;
        }
    }

    enum Mode {
        READ, WRITE
    }
}
