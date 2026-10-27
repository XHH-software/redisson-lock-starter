package com.xhh.lock.test;

import com.xhh.lock.annotation.Lock;
import org.springframework.stereotype.Service;

/**
 * 与 {@link DemoService#outer(long)} 使用同一把锁，用来验证可重入。
 */
@Service
public class InnerService {

    @Lock(key = "'nested:' + #id", waitTime = 0)
    public String inner(long id) {
        return "inner-" + id;
    }
}
