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

@Component
@Slf4j
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID>R queryWithPassThrough(String keyPrefix, ID id , Class<R>type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix+id;
        //1.从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if(json!=null){
            //返回一个错误信息
            return null;
        }
        //4 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5 不存在，返回错误
        if (r==null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6 存在，写入redis
        this.set(key,r,time,unit);
        //返回
        return r;
    }
    private  static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2 判断是否存在
        if (StrUtil.isBlank(json)) {
            //3 不存在，直接返回
            return null;
        }
        //4 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return r;
        }
        //5.2 已过期，需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        //【重要】锁的key必须和缓存key区分开！
        // 原来写成 keyPrefix + id 会和缓存key完全相同，因为能走到这行说明缓存key必然已存在，
        // setIfAbsent 永远返回 false -> 永远拿不到锁 -> 缓存永不重建 -> 数据永久脏
        String lockKey = "lock:" + keyPrefix + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if(isLock){
            //========双重检查！！再次查redis========
            json = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(json)){
                RedisData newRedisData = JSONUtil.toBean(json, RedisData.class);
                if(newRedisData.getExpireTime().isAfter(LocalDateTime.now())){
                    //别的线程已经重建好了，直接返回新数据，不要新建任务
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) newRedisData.getData(), type);
                }
            }
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    //【注意】数据不存在时不能写入 data:null，
                    //否则下次请求反序列化会NPE，且每次都击穿到数据库
                    if (r1 == null) {
                        log.warn("缓存重建失败，数据库中不存在该数据，key={}", key);
                        return;
                    }
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    log.error("缓存重建异步任务异常",e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //6.4 未获取到锁（说明已有线程在重建）或刚提交重建任务，先返回过期的旧数据
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
