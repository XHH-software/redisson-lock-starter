package com.xhh.lock.core;

import com.xhh.lock.config.LockProperties;
import com.xhh.lock.exception.LockKeyParseException;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.temporal.Temporal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code @Lock(key = "...")} 中的 SpEL 解析成真实的 Redis key。
 *
 * <p>支持两种写法：
 * <ul>
 *   <li>表达式：{@code "#orderId"}、{@code "'order:' + #order.id"}</li>
 *   <li>模板：{@code "order:#{#orderId}"}</li>
 * </ul>
 *
 * <p>为了让参数名可用，编译时必须保留 {@code -parameters}（本项目 pom 已开启）。
 * 否则 Spring 会退回到读取调试信息，IDE 增量编译时代价较大。
 *
 * <p>求值结果必须是<b>标量</b>（字符串 / 数字 / 布尔 / 字符 / 枚举 / UUID / 时间类型），
 * 或者是自己重写了 {@code toString()} 的值对象。详见 {@link #toKey(Object, String)}。
 */
public class LockKeyParser {

    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /** 模板解析上下文：只有显式传入它，SpEL 才会把 #{...} 当作占位符而不是普通字符 */
    private static final ParserContext TEMPLATE_CONTEXT = new ParserContext() {
        @Override
        public boolean isTemplate() {
            return true;
        }

        @Override
        public String getExpressionPrefix() {
            return "#{";
        }

        @Override
        public String getExpressionSuffix() {
            return "}";
        }
    };

    /** 模板里的占位符，用于逐段校验求值结果的类型 */
    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{([^{}]*)}");

    private final ExpressionParser parser = new SpelExpressionParser();
    private final LockProperties properties;

    public LockKeyParser(LockProperties properties) {
        this.properties = properties;
    }

    public String parse(String expression, Method method, Object[] args) {
        return parse(expression, method, args, null);
    }

    /**
     * @param target 目标对象，可为 null；传入后 SpEL 中可用 {@code #root}
     */
    public String parse(String expression, Method method, Object[] args, Object target) {
        String prefix = properties.getKeyPrefix() == null ? "" : properties.getKeyPrefix();

        if (!StringUtils.hasText(expression)) {
            // 未指定 key 时退化为「类名#方法名」，
            // 这样至少是方法级互斥，而不是所有未指定 key 的方法共用同一个空 key
            return prefix + method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }

        StandardEvaluationContext context = new MethodBasedEvaluationContext(target, method, args, NAME_DISCOVERER);
        try {
            String value;
            if (expression.contains(TEMPLATE_CONTEXT.getExpressionPrefix())) {
                // 模板是先拼接再返回 String，类型信息在拼接那一步就丢了，
                // 所以要在拼接之前把每个占位符单独求值校验一遍
                assertPlaceholdersAreKeys(expression, context);
                value = parser.parseExpression(expression, TEMPLATE_CONTEXT).getValue(context, String.class);
            } else {
                value = toKey(parser.parseExpression(expression).getValue(context), expression);
            }
            if (value == null) {
                throw new LockKeyParseException("锁 key 解析结果为 null, expression = " + expression, null);
            }
            return prefix + value;
        } catch (LockKeyParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LockKeyParseException("锁 key 解析失败, expression = " + expression, e);
        }
    }

    /**
     * 校验模板里的每个 {@code #{...}} 占位符，任一个不是标量就拒绝。
     *
     * <p>不校验的话，{@code "order:#{#order}"} 会得到 {@code order:Order@6f2b958e}，
     * 而且从返回值上看不出任何异常。
     */
    private void assertPlaceholdersAreKeys(String template, StandardEvaluationContext context) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String inner = matcher.group(1).trim();
            if (inner.isEmpty()) {
                continue;
            }
            toKey(parser.parseExpression(inner).getValue(context), "#{" + inner + "}");
        }
    }

    /**
     * 把 SpEL 求值结果转成 key 字符串。
     *
     * <p>只接受标量，或自己重写了 {@code toString()} 的值对象（{@code LocalDate}、枚举这类）。
     * 必须拦住的情况是：{@code @Lock(key = "#order")} 传了一个没重写 toString 的对象，
     * {@code String.valueOf} 会走 {@code Object#toString}，得到 {@code Order@6f2b958e}。
     * hashCode 每次调用都不同，同一业务本该互斥的请求会各自拿到一把全新的锁，
     * <b>互斥彻底失效，而且不报任何错</b>——比直接抛异常难查得多。
     */
    private String toKey(Object raw, String expression) {
        if (raw == null) {
            return null;
        }
        if (isScalar(raw) || overridesToString(raw.getClass())) {
            return String.valueOf(raw);
        }
        throw new LockKeyParseException(
                "锁 key 表达式 [" + expression + "] 的求值结果是 " + raw.getClass().getName()
                        + "，不能直接作为 key。请显式取标量字段，例如 \"#order.id\"。"
                        + "直接返回对象会走 Object#toString（默认带 hashCode），每次调用得到的 key 都不同，"
                        + "锁会静默失效。", null);
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum
                || value instanceof UUID
                || value instanceof Temporal;
    }

    /**
     * 类自己（含父类）是否重写了 {@code toString()}。
     *
     * <p>用来放行 {@code LocalDate}、Lombok {@code @Data} 生成的对象这类「toString 稳定」的值对象；
     * 没重写的类继承的是 {@code Object#toString}，输出里带 hashCode。
     */
    private static boolean overridesToString(Class<?> type) {
        try {
            return type.getMethod("toString").getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
