package com.xhh.lock.exception;

/**
 * SpEL 表达式解析失败时抛出。
 */
public class LockKeyParseException extends RuntimeException {

    public LockKeyParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
