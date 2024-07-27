package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        // 到redis中查询
        List<String> list = stringRedisTemplate.opsForList().range(key, 0, -1);

        // redis中有内容
        if(CollectionUtil.isNotEmpty(list)) {
            // 将List<String>转为List<ShopType>
            List<ShopType> shopTypes = list.stream()
                    .map(item -> JSONUtil.toBean(item, ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        // redis中没有则从数据库中查找
        // List<ShopType> shopTypes = lambdaQuery().orderByAsc(ShopType::getSort).list();
        List<ShopType> shopTypes = this.query().orderByAsc("sort").list();
        // 数据库无数据
        if(CollectionUtil.isEmpty(shopTypes)) {
            return Result.fail("商户类型为空");
        }
        // 将List<ShopType> 转为 List<String>
        List<String> shopTypeCache = shopTypes.stream()
                .sorted(Comparator.comparingInt(ShopType::getSort))
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        // 放入redis中
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeCache);
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypes);
    }
}
