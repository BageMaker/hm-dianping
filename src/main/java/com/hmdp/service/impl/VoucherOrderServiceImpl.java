package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.beans.Transient;
import java.nio.file.LinkOption;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 查看活动是否开启
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动还未开始");
        }
        // 查看活动是否过期
        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束");
        }
        // 查看库存是否充足
        if(voucher.getStock() < 1) {
            return Result.fail("优惠券库存不足");
        }

        // 给用户id上锁，实现一人一单
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order"+userId, stringRedisTemplate);
        boolean success = simpleRedisLock.tryLock(2);
        if(!success) {
            return Result.fail("不可重复购买");
        }
        try {
                // 使用事务的代理对象防止spring事务失效
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);
        } finally {
            simpleRedisLock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 查询是否存在订单
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        // 已经存在订单
        if(count > 0) {
            // 用户已经购买过
            return Result.fail("用户已经购买过");
        }
        // 优惠券处于活动期间，并且库存充足
        // 库存减1
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 解决超卖问题
                .update();
        if(!success) {
            return Result.fail("库存不足");
        }
        // 新增订单
        VoucherOrder  voucherOrder = new VoucherOrder();
        // 生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 设置订单信息，订单id、优惠券id、用户id
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        // 写入数据库
        save(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
    }
}
