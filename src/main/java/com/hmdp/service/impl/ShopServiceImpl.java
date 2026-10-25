package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,id2 -> getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿问题
        //Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,id2 -> getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
   }
   private  static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        //1.从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3 不存在，直接返回
            return null;
        }
        //4 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断是否过期（expireTime 可能为null，必须先判空，否则 isAfter 会抛NPE）
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return shop;
        }
        //5.2 已过期，需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY +id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if(isLock){
            //========双重检查！！再次查redis========
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if(StrUtil.isNotBlank(shopJson)){
                RedisData newRedisData = JSONUtil.toBean(shopJson, RedisData.class);
                LocalDateTime newExpireTime = newRedisData.getExpireTime();
                if(newExpireTime != null && newExpireTime.isAfter(LocalDateTime.now())){
                    //别的线程已经重建好了，释放锁并返回【新数据】
                    //【重要】原来这里 return shop（旧数据），应该返回刚拿到的新数据
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) newRedisData.getData(), Shop.class);
                }
            }
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,1200L);
                } catch (Exception e) {
                    log.error("缓存重建任务异常", e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //6.4 返回过期的商铺信息
        return shop;
    }
    public Shop queryWithMutex(Long id){
        //1.从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //4 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        boolean isLock = false;
        try {
            isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock){
                //4.3 失败，休眠后【循环重试】
                //【重要】原来用的是递归 queryWithMutex(id)，有两个问题：
                //  1) 数据库慢时递归层数线性增长，有 StackOverflowError 风险
                //  2) 递归返回后每一层的 finally 都会执行 unlock，
                //     而未持锁的线程会删掉【别人持有】的锁，导致互斥失效
                // 现在改成 while 循环重试，并且只在真正持锁时才释放锁
                while (!isLock) {
                    Thread.sleep(50);
                    shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                    if (StrUtil.isNotBlank(shopJson)) {
                        //别人已经重建好了，直接返回
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }
                    if (shopJson != null) {
                        //命中的是空值缓存
                        return null;
                    }
                    isLock = tryLock(lockKey);
                }
            }
            //4.4 成功，根据id查询
            shop = queryWithPassThrough(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }finally {
            //7 释放互斥锁：只有【确实持有锁】的线程才有权释放，防止误删别人的锁
            if (isLock) {
                unlock(lockKey);
            }
        }
        //8返回
        return shop;
    }
    public Shop queryWithPassThrough(Long id){
        //1.从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //4 不存在，根据id查询数据库
        Shop shop = getById(id);
        //5 不存在，返回错误
        if (shop==null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6 存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    public void saveShop2Redis(Long id,Long expireSeconds){
        //1 查询店铺数据
        Shop shop = getById(id);
        //2 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Transactional
    @Override
    public Result update(Shop shop) {
        if (shop.getId()==null) {
            return Result.fail("店铺id不能为空");
        }
        //1 更新数据库
        updateById(shop);
        //2 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x==null || y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from =(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end =current*SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离排序分页。结果：shopId,distance
        String key = SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4.解析出id
        if (results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long>ids=new ArrayList<>(list.size());
        Map<String,Distance>distanceMap = new HashMap<>(list.size());
        //4.1截取from-end的部分
        list.stream().skip(from).forEach(result ->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        // 这里新增ids判空：GEO查不到数据时直接返回空列表
        if (ids.isEmpty()) {
            //打警告日志：页面"空白"最常见的原因就是Redis里没有GEO数据
            log.warn("店铺GEO查询结果为空：typeId={}，请确认Redis中是否存在 {} 键（启动时会由ShopGeoInitializer自动预加载）", typeId, key);
            return Result.ok(Collections.emptyList());
        }
        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }
}
