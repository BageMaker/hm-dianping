package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

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

    @Resource
    private RedissonClient redissonClient;

    // 读取脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024*1024);
    // 线程池
    public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 代理对象
    private IVoucherOrderService proxy;

    // 线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    // 从阻塞队列中取出任务
                    VoucherOrder voucherOrder = orderTask.take();
                    // 创建订单
                    handlerVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("线程执行出错"+e);
                }
            }
        }

        @PostConstruct
        private void init() {
            SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        }

        private void handlerVoucherOrder(VoucherOrder voucherOrder) {
            // 获取用户id
            long userId = voucherOrder.getUserId();
            // 获取锁（可重入），自定义锁的名称
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            // 尝试获取锁，设置1s内反复尝试，ttl设置为10秒
//        boolean success = lock.tryLock(1, 10, TimeUnit.SECONDS);
            // 获取锁
            boolean success = lock.tryLock();
            if(!success) {
                log.error("不允许重复下单");
                return;
            }
            try {
                // 使用事务的代理对象防止spring事务失效
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }

        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本，解决超卖和一人一单的问题
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int res = result.intValue();
        // lua返回结果不为0.没有资格
        if(res != 0) {
            return Result.fail(res == 1 ? "库存不足" : "不可重复下单" );
        }
        // 有资格，将优惠券id，用户id，订单id等信息存到阻塞队列中
        // 新增订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 把订单放到阻塞队列中
        orderTask.add(voucherOrder);
        // 获取代理对象，让异步操作使用
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 一人一单
        // 根据用户id查询数据库判断是否已经下过单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 已经下过单
        if(count > 0) {
            log.error("一人只能买一张");
            return;
        }
        // 下订单
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if(!success) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }

    /*
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
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order"+userId, stringRedisTemplate);
//        boolean success = simpleRedisLock.tryLock(2);
        // 获取锁（可重入），自定义锁的名称
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁，设置1s内反复尝试，ttl设置为10秒
//        boolean success = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 获取锁
        boolean success = lock.tryLock();
        if(!success) {
            return Result.fail("不可重复购买");
        }
        try {
                // 使用事务的代理对象防止spring事务失效
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);
        } finally {
//            simpleRedisLock.unlock();
            lock.unlock();
        }
    } */

    /*
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
    */
}
