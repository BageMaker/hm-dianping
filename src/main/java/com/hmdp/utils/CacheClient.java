package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意对象序列化为JSON并存储到string类型的key中，并且设置TTL
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    //将任意对象序列化为JSON并存储到string类型的key中，并且设置逻辑过期时间，用于处理缓存击穿
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 将商户信息和逻辑过期时间封装
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写到redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 在redis中查询商户
        String jsonObj = stringRedisTemplate.opsForValue().get(key);
        // 存在，返回
        if(StrUtil.isNotBlank(jsonObj)) {
            return JSONUtil.toBean(jsonObj, type);
        }
        // 如果redis中为“”空字符串，返回null
        if(jsonObj != null) {
            return null;
        }
        // 不存在，在mysql数据库中查询
        R r = dbFallBack.apply(id);
        // 不存在，返回错误信息
        if(r == null) {
            // 把空值写道redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，将查询到的数据放到redis中
        this.set(key, r, time, unit);

        // 返回数据
        return r;
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit
    ) {
        String key = keyPrefix + id;
        // 在redis中查询商户
        String json = stringRedisTemplate.opsForValue().get(key);
        // 未命中，返回null
        if(StrUtil.isBlank(json)) {
            return null;
        }
        // 命中，将json反序列为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 判断逻辑缓存是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        // 未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 已过期，缓存重建
        String lockKey = LOCK_SHOP_KEY+id;
        // 获取锁
        boolean isLock = trylock(lockKey);
        // 获取到锁，开启线程，实现缓存重建
        if(isLock) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 返回信息
        return r;
    }

    // 获取锁
    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
