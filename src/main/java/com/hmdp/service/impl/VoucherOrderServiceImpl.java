package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代金券订单服务实现类
 * 秒杀方案：Lua脚本预校验 + 阻塞队列异步创建订单
 * 核心思想：
 * 1. 秒杀请求先到Redis执行Lua脚本，完成库存判断、一人一单判断，速度极快
 * 2. 校验通过后，订单信息放入阻塞队列，立刻返回订单号给前端
 * 3. 后台单线程从阻塞队列取出任务，异步操作数据库创建订单，降低数据库压力
 */
@Service
@Slf4j // lombok日志注解，使用log打印日志
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    // 秒杀券服务
    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    // Redis分布式ID生成器
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // Redisson客户端，用于分布式锁
    @Autowired
    private RedissonClient redissonClient;
    // Spring容器，用于在@PostConstruct里安全地拿到AOP代理对象
    @Autowired
    private ApplicationContext applicationContext;

    // 加载秒杀Lua脚本，静态常量，类加载时只加载一次
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 读取resources下的seckill.lua脚本文件
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 指定脚本返回值类型为Long
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列：存放待处理的秒杀订单任务
    // 容量1024*1024，课程演示使用，生产不建议这么大
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 单线程线程池，专门异步处理秒杀订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 事务代理对象，用来调用带事务的createVoucherOrder方法
    private IVoucherOrderService proxy;

    /**
     * @PostConstruct：Bean初始化完成之后自动执行
     * 开启后台订单处理线程
     */
    @PostConstruct
    private void init() {
        // 从容器中获取当前类的AOP代理对象，保证事务生效
        // 注意：不能用 AopContext.currentProxy()！@PostConstruct 执行时机在
        // postProcessBeforeInitialization 阶段，此时AOP代理尚未创建，
        // 会导致 IllegalStateException: Cannot find current proxy
        proxy = applicationContext.getBean(IVoucherOrderService.class);
        // 提交订单任务处理器到线程池，后台持续运行
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 内部类：订单任务处理器，后台线程循环执行
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            // 死循环，不断从阻塞队列获取订单任务
            while (true) {
                try {
                    // take()：阻塞方法，队列空的时候线程阻塞等待，不会空轮询消耗CPU
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 处理订单：加锁、操作数据库创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }

        /**
         * 处理秒杀订单
         * @param voucherOrder 待创建订单对象
         */
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            // 创建Redisson分布式锁，key：lock:order:userId，保证一人一单
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            // 尝试获取锁（无等待）
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单");
                return;
            }
            try {
                // 使用代理对象调用带事务的方法，保证@Transactional生效
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 释放锁前判断：只有当前线程持有锁才释放，防止释放别人的锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * 秒杀下单入口方法
     * @param voucherId 秒杀券id
     * @return Result，成功返回订单id，失败返回提示信息
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 执行Lua脚本，参数：脚本，key集合，voucherId、userId
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        int r = result.intValue();
        // 脚本返回值不等于0：不满足下单条件
        if (r != 0) {
            // r=1：库存不足；r=2：用户重复下单
            return Result.fail(r == 1 ? "库存不足" : "无法重复下单");
        }

        // Lua校验通过，构建订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        // 生成全局唯一订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 将订单放入阻塞队列，交给后台线程异步处理数据库
        orderTasks.add(voucherOrder);

        // 直接返回订单号给前端，前端下单请求结束
        return Result.ok(orderId);
    }

    /**
     * 创建订单（带事务），后台线程调用，操作数据库
     * @param voucherOrder 订单信息
     * @return Result
     */
    @Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 一人一单：查询当前用户是否已经买过该优惠券
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return Result.fail("用户已经购买过一次！");
        }

        // 扣减库存，使用乐观锁：stock > 0才扣减，防止超卖
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return Result.fail("库存不足！");
        }

        // 保存订单到数据库
        save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}