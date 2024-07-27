package com.hmdp.utils;

import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    // 模块名，与锁前缀拼接后作为redis中的key
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // redis中锁前缀
    private static final String KEY_PREFIX = "lock:";
    // 锁前缀
    public static final String ID_PREFIX = UUID.randomUUID().toString()+"-";

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中标识
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        // 判断标识是否一致
        if(threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }

    }
}