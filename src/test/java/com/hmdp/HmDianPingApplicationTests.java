package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.yaml.snakeyaml.events.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
    @Test
    void loadShopData(){
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.把店铺分组，按照typeId分组
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入redis
        for (Map.Entry<Long,List<Shop>>entry:map.entrySet()){
            //3.1获取类型id
            Long typeId = entry.getKey();
            String key =SHOP_GEO_KEY+typeId;
            //3.2获取同类型的店铺的集合
            List<Shop> value =entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //3.3写入redis GEOADD key 经度，纬度 member
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }

    }
}
