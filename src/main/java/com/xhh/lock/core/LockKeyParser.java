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
                value = parser.parseExpression(expression, TEMPLATE_CONTEXT).getValue(context, String.class);
            } else {
                Object raw = parser.parseExpression(expression).getValue(context);
                value = raw == null ? null : String.valueOf(raw);
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
}
