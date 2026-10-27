# redisson-lock-starter

> 一个基于 Redisson 的注解式分布式锁 Starter：业务侧只写一行 `@Lock`，锁的解析、获取、续期、释放全部交给组件。
> 重点解决了 **「锁先于事务释放」** 这个注解式锁最容易踩的坑。

---

## 目录

- [为什么写这个组件](#为什么写这个组件)
- [特性](#特性)
- [快速开始](#快速开始)
- [注解参数](#注解参数)
- [配置项](#配置项)
- [核心设计](#核心设计)
  - [1. SpEL 动态锁名](#1-spel-动态锁名)
  - [2. 切面顺序：锁必须在事务外层](#2-切面顺序锁必须在事务外层)
  - [3. 事务同步兜底](#3-事务同步兜底)
  - [4. 看门狗与租约](#4-看门狗与租约)
- [测试](#测试)
- [目录结构](#目录结构)
- [已知限制](#已知限制)
- [Roadmap](#roadmap)

---

## 为什么写这个组件

`@Transactional` 加锁这件事，写在业务代码里通常长这样：

```java
public void deduct(Long skuId, int count) {
    RLock lock = redissonClient.getLock("stock:" + skuId);
    lock.lock();
    try {
        // 查库存、判断、扣减
    } finally {
        lock.unlock();
    }
}
```

三个问题：

1. **重复代码**：每个需要互斥的方法都要套一遍 try-finally，漏写 `unlock` 就是死锁。
2. **锁名难统一**：有人写 `stock:`，有人写 `lock:stock:`，同一个资源两把锁，等于没加锁。
3. **顺序容易错**：如果直接给方法加 `@Transactional` + `@Lock`，切面顺序没调好，
   就会出现 **锁先释放、事务后提交**：

   ```
   线程 A：加锁 → 开事务 → 改库存 → 释放锁 → 提交
   线程 B：                └── 拿到锁 → 读到旧库存 → 也扣一次
   ```

   这是库存超卖、余额少扣的经典成因，而且**本地压测不一定能复现**，线上偶发。

本组件的做法是：把 1、2 交给注解和 SpEL，把 3 用**切面 order + 事务同步**两层保证解决掉。

---

## 特性

| 能力 | 说明 |
|---|---|
| `@Lock` 注解 | 一行注解完成加锁，无需 try-finally |
| SpEL 动态锁名 | `#orderId`、`'order:' + #order.id`、模板 `order:#{#orderId}` |
| 四种锁类型 | 可重入 / 公平 / 读锁 / 写锁（`LockType`） |
| 等待与租约可配 | 注解级 + 全局默认，注解优先 |
| 默认看门狗续期 | `leaseTime = -1` 时不设固定过期时间，业务执行多久锁就续多久 |
| 事务感知释放 | 锁不会早于事务结束释放，无论切面谁在外层 |
| 失败可观测 | 获取失败抛 `LockAcquireException`（带 key），可选 warn 日志 |
| 零配置接入 | 引入依赖 + `spring.redis.*` 即可，无 `@EnableXxx` |

---

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.xhh</groupId>
    <artifactId>redisson-lock-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

组件已传递依赖 `redisson-spring-boot-starter` 与 `spring-boot-starter-aop`，
业务方不需要再单独引入。

### 2. 配置 Redis

```yaml
spring:
  redis:
    host: 127.0.0.1
    port: 6379
    password: 
```

### 3. 加注解

```java
@Service
public class OrderService {

    @Lock(key = "'order:' + #orderId")
    public void createOrder(Long orderId) {
        // 这里已经是互斥执行
    }
}
```

就这三步。没有 `@EnableXxx`，也不需要手写 `RedissonClient`。

### 4. 处理获取失败

`waitTime` 内没抢到锁时抛 `LockAcquireException`：

```java
try {
    orderService.createOrder(orderId);
} catch (LockAcquireException e) {
    // 可选：转成业务异常 / 提示「操作过于频繁，请稍后重试」
    throw new BizException("系统繁忙，请稍后重试");
}
```

---

## 注解参数

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Lock {
    String key() default "";
    LockType type() default LockType.REENTRANT;
    long waitTime() default -1;
    long leaseTime() default -1;
}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `key` | `""` | SpEL 表达式。**留空时退化为 `类名#方法名`**（方法级互斥），不会出现所有方法共用一把空 key |
| `type` | `REENTRANT` | `REENTRANT` / `FAIR` / `READ` / `WRITE` |
| `waitTime` | `-1` | 最长等待毫秒数。`-1` 沿用全局配置；`0` 表示不等待、立即失败 |
| `leaseTime` | `-1` | 持有毫秒数。`-1` 启用看门狗自动续期；指定值则到点强制释放 |

**key 会统一拼接全局前缀**（默认 `lock:`）。所以 `@Lock(key = "#id")` 在 Redis 里的实际 key 是 `lock:42`。

### 用法示例

```java
// 简单参数
@Lock(key = "#userId")
public void updateProfile(Long userId) { ... }

// 对象属性
@Lock(key = "'order:' + #order.id", waitTime = 500)
public void pay(Order order) { ... }

// 模板写法（等价于上面，看团队习惯）
@Lock(key = "order:#{#order.id}")
public void pay2(Order order) { ... }

// 读多写少：读锁之间不互斥
@Lock(key = "'config:' + #key", type = LockType.READ)
public String getConfig(String key) { ... }

@Lock(key = "'config:' + #key", type = LockType.WRITE)
public void setConfig(String key, String value) { ... }

// 公平锁：按请求顺序获取，适合「先到先得」有业务含义的场景
@Lock(key = "'queue:' + #id", type = LockType.FAIR)
public void enqueue(Long id) { ... }
```

---

## 配置项

```yaml
xhh:
  lock:
    enabled: true          # 总开关，false 时组件完全不装配
    key-prefix: "lock:"    # 锁 key 统一前缀
    wait-time: 3000        # 默认最长等待（ms）
    lease-time: -1         # 默认持有时间（ms），-1 = 看门狗
    log-on-fail: true      # 抢锁失败是否打 warn 日志
```

| 配置 | 默认值 | 说明 |
|---|---|---|
| `xhh.lock.enabled` | `true` | 设为 `false` 可整体关闭（`@Lock` 变成无操作） |
| `xhh.lock.key-prefix` | `lock:` | 便于在 Redis 里区分业务数据，也方便按前缀清理 |
| `xhh.lock.wait-time` | `3000` | 注解 `waitTime = -1` 时使用 |
| `xhh.lock.lease-time` | `-1` | 注解 `leaseTime = -1` 时使用 |
| `xhh.lock.log-on-fail` | `true` | 抢锁失败告警 |

---

## 核心设计

### 1. SpEL 动态锁名

`LockKeyParser` 用 Spring 的 `MethodBasedEvaluationContext` 把注解里的表达式求值成真实 key，
参数名依赖编译期的 `-parameters`（本项目 pom 已开启，业务方用 Maven 默认配置即可）。

两种写法走两条路径：

```java
if (expression.contains("#{")) {
    // 模板：必须传 TemplateParserContext，否则 SpEL 会把 #{...} 当普通字符
    value = parser.parseExpression(expression, TEMPLATE_CONTEXT).getValue(context, String.class);
} else {
    value = parser.parseExpression(expression).getValue(context);
}
```

细节处理：

- **解析结果为 `null` 直接抛异常**，而不是拼出 `lock:null` 去操作 Redis——
  那样所有解析失败的方法会挤在同一把锁上，表面正常、实际完全丧失互斥。
- **key 为空时退化为 `类名#方法名`**，保证至少是方法级互斥。
- 异常统一包装成 `LockKeyParseException`，不把 SpEL 内部的 `SpelEvaluationException` 泄漏到业务层。

### 2. 切面顺序：锁必须在事务外层

Spring 的 `@Transactional` 默认 order 是 `Ordered.LOWEST_PRECEDENCE`，**order 越小越靠外层**。
本组件的 `LockAspect` 取 `LOWEST_PRECEDENCE - 10`：

```
        order 小（外层）                                 order 大（内层）
    ┌──────────────────────────────────────────────────────────────┐
    │  LockAspect                     TransactionInterceptor       │
    │  加锁  ──────────────────────▶  开启事务 → 业务 → 提交事务  │
    └──────────────────────────────────────────────────────────────┘
                                                              ↓
                                                          释放锁
```

顺序对了，事务提交完才释放锁，窗口不存在。

反过来（锁在内层）就会变成「释放锁 → 提交事务」，中间那几毫秒足够另一个线程读到旧数据。

### 3. 事务同步兜底

order 是可以被业务方覆盖的——如果某个方法写了 `@Transactional(order = 0)`，事务切面就会跑到锁切面外面。
为了不管谁在外层都安全，`finally` 里的释放动作交给 `LockReleaseSupport` 判断：

```java
public static void releaseAfterTransaction(Runnable unlockAction) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                unlockAction.run();   // 事务提交或回滚之后才真正 unlock
            }
        });
        return;
    }
    unlockAction.run();  // 没有事务，立即释放
}
```

**为什么必须判断 `isActualTransactionActive()`？**
只看 `isSynchronizationActive()` 时，在 `PROPAGATION_SUPPORTS` 这类「有同步器但没有真实事务」的场景下，
会注册一个永远不会被回调的同步器，锁从此泄漏。这个分支在单测里容易被漏掉。

`afterCompletion` 而不是 `afterCommit`：业务抛异常导致回滚时同样要释放，否则一次异常就能把这把锁永久占住。

### 4. 看门狗与租约

| `leaseTime` | 行为 | 适用 |
|---|---|---|
| `-1`（默认） | Redisson 每 10s 续期一次（默认 30s 过期） | **绝大多数场景**，业务执行时长不可预估 |
| 指定值 | 到点强制释放，看门狗失效 | 明确知道上限、且宁可并发也不愿锁被长期占用的场景 |

释放时先判断 `isHeldByCurrentThread()` 再 `unlock()`：

```java
if (!rLock.isHeldByCurrentThread()) {
    log.warn("锁 [{}] 已不归当前线程持有（可能是 leaseTime 到期自动释放），跳过 unlock", key);
    return;
}
```

原因：`leaseTime` 设短了、业务又跑超时，锁已经被 Redisson 释放，此时 `unlock()` 会抛
`IllegalMonitorStateException`。这个异常出现在 `finally` 里会**覆盖掉真正的业务异常**，
排查时会指向完全错误的代码行。

---

## 测试

```bash
# 只跑单测（不需要 Redis）
mvn test -Dtest=LockKeyParserTest

# 全量（需要 Docker，Testcontainers 会拉起 redis:7.4-alpine）
mvn test
```

本机没有 Docker 时，`@Testcontainers(disabledWithoutDocker = true)` 会自动跳过集成测试，
`mvn test` 仍然是绿的——**不会因为环境缺失把构建搞红**。

| 用例 | 验证点 |
|---|---|
| `shouldRejectWhenLockHeld` | 锁被占用时，`waitTime = 0` 立即失败 |
| `shouldFailAfterWaitTimeout` | `waitTime = 300ms` 时等满 300ms 再失败，且不会一直阻塞到锁释放 |
| `shouldSupportReentrant` | 同线程跨 Bean 重复加同一把锁成功，解锁后计数归零 |
| `shouldReleaseLockAfterRollback` | 事务回滚后锁被释放，不会永久占用 |
| `shouldReleaseLockAfterCommit` | 事务提交后锁被释放 |
| `shouldUseConfiguredKeyPrefix` | 全局前缀生效 |
| `LockKeyParserTest` × 7 | SpEL / 模板 / 空 key / 空前缀 / null 结果 / 语法错误 |

---

## 目录结构

```
src/main/java/com/xhh/lock/
├── annotation/
│   ├── Lock.java                    # @Lock 注解
│   └── LockType.java                # 可重入 / 公平 / 读 / 写
├── config/
│   ├── LockAutoConfiguration.java   # 自动装配入口
│   └── LockProperties.java          # xhh.lock.* 配置
├── core/
│   ├── LockAspect.java              # 切面：加锁、执行、释放
│   ├── LockKeyParser.java           # SpEL → 真实 key
│   └── LockReleaseSupport.java      # 事务感知的释放时机
└── exception/
    ├── LockAcquireException.java    # 抢锁失败
    └── LockKeyParseException.java   # 表达式解析失败
```

---

## 已知限制

诚实地列出来，比让使用者踩坑好：

1. **同类内部调用不生效**。`this.otherMethod()` 不经过代理，`@Lock` 不会被拦截。
   需要重入请跨 Bean 调用，或注入自身代理。
2. **JDK 动态代理下注解需能被解析到**。注解写在实现类方法上时，组件会通过
   `AopUtils.getMostSpecificMethod` 回落到实现方法，正常可用；但接口 + 实现各写一份注解时以实现类为准。
3. **锁不是事务的替代品**。它只保证同一 key 的临界区互斥，跨 key 的一致性问题
   （比如同时扣两个商品的库存）仍需业务侧排序锁 key 或改用其他方案。
4. **不做锁降级**。Redis 不可用时直接抛异常，不会退化成单机锁——
   退化成单机锁在多实例下是**假互斥**，比失败更危险。
5. **`#root` 需要目标对象**。通过 `parse(expr, method, args, target)` 传入 target 后可用；
   简化的三参重载等价于 target 为 null。

---

## Roadmap

- [ ] 获取失败的自定义降级策略（`onFail = FAIL_FAST | SKIP | BLOCK`）
- [ ] 锁等待与持有时长的 Micrometer 指标
- [ ] 多 Redis 节点下的 `RedLock` 支持
- [ ] 注解支持 `@AliasFor`，兼容自定义组合注解

---

## License

[MIT](LICENSE)
