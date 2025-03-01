package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    // 开始时间戳
    private static final long BEGIN_TIMESTAMP = 1711843200L;

    private StringRedisTemplate stringRedisTemplate;

    // 序列号的位数
    private static final int COUNT_BITS = 32;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 获取id
    public long nextId(String keyPrifix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond-BEGIN_TIMESTAMP;
        // 生成序列号
        // 获取当前日期，精准到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 自增长
        long count = stringRedisTemplate.opsForValue().increment("incr:" + keyPrifix + ":" + date);

        // 拼接并返回
        return timeStamp<<COUNT_BITS | count;
    }
}
