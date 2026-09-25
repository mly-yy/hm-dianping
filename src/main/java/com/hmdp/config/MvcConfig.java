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
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",     // 发送短信验证码：登录前就要访问
                        "/user/login",    // 登录接口：登录前就要访问
                        "/blog/hot",      // 热门博客：未登录也能看
                        "/shop/**",       // 店铺相关：未登录也能看
                        "/shop-type/**",  // 店铺类型：首页要展示，未登录也能看
                        "/upload/**",     // 上传的静态资源
                        "/voucher/**"     // 优惠券：未登录也能看
                ).order(1);
        //token刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
