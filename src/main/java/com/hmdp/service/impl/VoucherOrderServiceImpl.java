package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 代金券订单服务实现类
 * 秒杀方案：Lua脚本预校验 + Redis Stream 消息队列异步创建订单
 * 核心思想：
 * 1. 秒杀请求先到Redis执行Lua脚本，完成库存判断、一人一单判断，速度极快
 * 2. 校验通过后，Lua脚本直接把订单消息XADD到Redis Stream，立刻返回订单号给前端
 * 3. 后台单线程从Stream读取消息，异步操作数据库创建订单，降低数据库压力
 */
@Service
@Slf4j // lombok日志注解，使用log打印日志
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService, Runnable {

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

    // 单线程线程池，专门异步处理秒杀订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 事务代理对象，用来调用带事务的createVoucherOrder方法
    private IVoucherOrderService proxy;

    // ==================== Redis Stream 相关常量 ====================
    // 消息队列名称：必须和 seckill.lua 里 XADD 的 key 完全一致！
    // Lua 里是 'stream.orders'，少写一个 s 就会连不上消息
    private static final String QUEUE_NAME = "stream.orders";
    // 消费者组名称
    private static final String GROUP_NAME = "g1";
    // 消费者名称
    private static final String CONSUMER_NAME = "c1";

    /**
     * @PostConstruct：Bean初始化完成之后自动执行
     * 1. 拿到AOP代理对象（保证事务生效）
     * 2. 创建消费者组
     * 3. 开启后台订单处理线程
     */
    @PostConstruct
    private void init() {
        // 从容器中获取当前类的AOP代理对象，保证事务生效
        // 注意：不能用 AopContext.currentProxy()！@PostConstruct 执行时机在
        // postProcessBeforeInitialization 阶段，此时AOP代理尚未创建，
        // 会导致 IllegalStateException: Cannot find current proxy
        proxy = applicationContext.getBean(IVoucherOrderService.class);

        // 创建消费者组（必须！XREADGROUP 要求组已存在，否则报 NOGROUP 错误）
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(QUEUE_NAME, ReadOffset.from("0"), GROUP_NAME);
        } catch (Exception e) {
            // 组已存在（BUSYGROUP）属于正常情况，忽略即可；
            // 其它异常也只记录日志，不能因为建组失败就让整个应用起不来
            log.debug("消费者组已存在或创建失败：{}", e.getMessage());
        }

        // 提交订单任务处理器到线程池，后台持续运行
        SECKILL_ORDER_EXECUTOR.submit(this);
    }

    /**
     * 后台线程：死循环不断从Redis Stream读取订单消息并处理
     * 由 SECKILL_ORDER_EXECUTOR 提交执行
     */
    @Override
    public void run() {
        while (true) {
            try {
                // 1. 获取消息队列中的订单信息
                //    XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                );
                // 2. 判断消息获取是否成功
                if (list == null || list.isEmpty()) {
                    // 2.1 获取失败，说明没有消息，继续下一次循环
                    continue;
                }
                // 3. 解析消息中的订单
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 4. 获取成功，创建订单
                handleVoucherOrder(voucherOrder);
                // 5. ACK确认：XACK stream.orders g1 <消息id>
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理订单异常", e);
                // 出现异常时去处理 pending-list 中未确认的消息，避免消息丢失
                handlePendingList();
            }
        }
    }

    /**
     * 处理 pending-list（已投递但未ACK的消息）
     * 用于异常恢复：正常流程中消息处理失败会进入这里重试
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1. 获取pending-list中的订单信息
                //    注意：偏移量必须是 ReadOffset.from("0")，表示读取"已投递给本消费者但未确认"的消息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );
                // 2. 判断消息获取是否成功
                if (list == null || list.isEmpty()) {
                    // 2.1 pending-list 已处理完，结束循环，回到主循环
                    break;
                }
                // 3. 解析消息中的订单
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 4. 获取成功，创建订单
                handleVoucherOrder(voucherOrder);
                // 5. ACK确认
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                try {
                    // 出现异常，休眠20毫秒后重试
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    // 恢复中断标记，避免吞掉中断信号
                    Thread.currentThread().interrupt();
                    return;
                }
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
        // 尝试获取锁：等待最多3秒，拿到后10秒自动释放（防止持锁线程宕机导致死锁）
        boolean isLock;
        try {
            isLock = lock.tryLock(3, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取分布式锁被中断，订单号：{}", voucherOrder.getId());
            return;
        }
        if (!isLock) {
            // 拿到不锁说明同一用户的另一笔订单正在处理中，不能直接丢单
            log.error("获取分布式锁失败，订单号：{}，userId：{}", voucherOrder.getId(), userId);
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

    /**
     * 秒杀下单入口方法
     * @param voucherId 秒杀券id
     * @return Result，成功返回订单id，失败返回提示信息
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");

        // 执行Lua脚本，参数：脚本，key集合，voucherId、userId、orderId
        // 脚本内部完成：库存判断 -> 一人一单判断 -> 扣库存 -> 记录用户 -> XADD发送消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        if (result == null) {
            // 理论上不会发生，防御性判空，避免拆箱NPE
            return Result.fail("秒杀失败，请稍后重试");
        }
        int r = result.intValue();
        // 脚本返回值不等于0：不满足下单条件
        if (r != 0) {
            // r=1：库存不足；r=2：用户重复下单
            return Result.fail(r == 1 ? "库存不足" : "无法重复下单");
        }
        // 直接返回订单号给前端，前端下单请求结束
        return Result.ok(orderId);
    }

    /**
     * 创建订单（带事务），后台线程通过代理对象调用，操作数据库
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
