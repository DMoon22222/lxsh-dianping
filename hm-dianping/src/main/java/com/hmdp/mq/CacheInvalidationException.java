package com.hmdp.mq;

public class CacheInvalidationException extends RuntimeException {

    public CacheInvalidationException(String message) {
        super(message);
    }

    public CacheInvalidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
