package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import com.hmdp.utils.RedisData;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    // 线程池

    @Override
    public Result queryById(Long id) {

        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存穿透
        // Shop shop = queryWithLogicalExpire(id);
        // Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("商户不存在");
        }
        // 返回信息
        return Result.ok(shop);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) return Result.fail("商户id为空");
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    /*
    // 互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 在redis中查询商户
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 存在，则返回
        if(StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 如果redis中的为空字符串，返回
        if(shopJson != null) {
            return null;
        }
        Shop shop = null;
        String lockKey = "lock:shop"+id;
        try {
            //获取锁
            boolean isLocked = trylock(lockKey);
            // 判断是否获取锁
            if(!isLocked) {
                // 没获取，重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取锁，重建缓存
            // Double Check
            String s = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(s)) {
                return JSONUtil.toBean(s, Shop.class);
            }

            if(s != null) {
                return null;
            }

            // 不存在，在mysql数据库中查询
            shop = getById(id);
            // 模拟延迟
            Thread.sleep(200);

            // 不存在，返回错误信息
            if(shop == null) {
                // 空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 存在，将查询的数据放到redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unlock(lockKey);
        }

        // 返回信息
        return shop;
    }

    // 缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 在redis中查询商户
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 存在，则返回
        if(StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 如果redis中的为空字符串，返回
        if(shopJson != null) {
            return null;
        }

        // 不存在，在mysql数据库中查询
        Shop shop = getById(id);

        // 不存在，返回错误信息
        if(shop == null) {
            // 空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 存在，将查询的数据放到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 返回信息
        return shop;
    }

    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        // 数据库中查询商户
        Shop shop = getById(id);
        Thread.sleep(200);
        // 将商户信息和逻辑过期时间封装
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }

    // 逻辑过期解决缓存穿透
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 在redis中查询商户
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);

        // 未命中，则返回null
        if(StrUtil.isBlank(redisDataJson)) {
            return null;
        }
        // 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // 判断缓存中 逻辑时间是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        // 未过期，返回shop
        if(expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 已过期，重建缓存
        String lockKey = LOCK_SHOP_KEY+id;
        // 获取锁
        boolean isLock = trylock(lockKey);
        // 获取到锁，开启独立线程，实现缓存重建
        if(isLock) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 返回信息
        return shop;
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
     */
}
