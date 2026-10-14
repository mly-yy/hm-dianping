package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 注册登录拦截器
     * 拦截规则：默认拦截所有请求，只有下面excludePathPatterns中列出的路径无需登录即可访问
     * 注意：排除路径必须以 "/" 开头才能匹配成功！
     * 之前 "shop-type/**" 少了 "/"，导致首页查询店铺类型的接口 /shop-type/list
     * 被拦截返回401，页面数据加载不出来（这就是"页面打不开"的原因之一）
     *
     * 【重要】放行范围必须精确到"只读接口"，不能整个模块放行！
     * 之前写成 "/shop/**"、"/voucher/**"、"/upload/**" 会把写接口也一起放行，匿名用户可以：
     *   - PUT  /shop                改写数据库店铺数据并删除缓存
     *   - POST /voucher/seckill     凭空创建秒杀券并写 Redis 库存
     *   - GET  /upload/blog/delete  删除服务器上的文件
     * 现在改为精确匹配 GET 读接口，写接口（POST/PUT）一律需要登录。
     *
     * 匹配规则说明：
     *   "/shop/*"    只匹配 /shop/{id} 这种【单层】路径（如 /shop/1）
     *   "/shop/of/**" 匹配 /shop/of/type、/shop/of/name 这种【多层】路径
     *   两者都不会匹配到 POST/PUT 的 "/shop" 本身，所以写接口安全
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",        // 发送短信验证码：登录前就要访问
                        "/user/login",       // 登录接口：登录前就要访问
                        "/blog/hot",         // 热门博客：未登录也能看
                        "/shop/*",           // 店铺详情（单层）：未登录也能看
                        "/shop/of/**",       // 店铺分页/搜索：首页要展示，未登录也能看
                        "/shop-type/**",     // 店铺类型：首页要展示，未登录也能看
                        "/voucher/list/**"   // 优惠券列表：未登录也能看（新增券的POST不放行）
                ).order(1);
        //token刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
