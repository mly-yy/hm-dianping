package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.yaml.snakeyaml.events.Event;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    void testIdWorker() throws InterruptedException {
        //线程池写在方法里面！局部！每次跑测试新建！
        ExecutorService es = Executors.newFixedThreadPool(300);
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i =0;i<100; i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id = "+id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i =0;i<300; i++){
            es.submit(task);
        }
        latch.await();
        es.shutdown();
        //❗加上这一句！等待真正关闭完成！
        es.awaitTermination(10, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();
        System.out.println("time=" + (end-begin));
    }
    /**
     * 缓存预热测试方法
     * id=1：数据库里的店铺编号
     * 1200L：逻辑过期时间，单位【秒】，1200秒 =20分钟
     */
    @Test
    void testSaveShop() throws InterruptedException{

        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+ 1L,shop,10L, TimeUnit.SECONDS);
    }
}
