package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate =stringRedisTemplate;
    }

    /**
     * 前置拦截：请求到达Controller之前执行
     * 作用：校验用户是否登录（请求头中是否携带合法token）
     * @return true放行，false拦截（返回401状态码，前端收到401会跳转到登录页）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token（前端登录成功后会把token存起来，之后每次请求放在authorization请求头中）
        String token = request.getHeader("authorization");
        // 2.判断token是否为空
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 3.基于token到redis中查询用户信息（登录时保存的Hash结构）
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        // 4.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        // 5.存在，将redis中的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.保存用户信息到ThreadLocal
        //    ThreadLocal是线程隔离的，每个请求独占一个线程，所以这里保存的用户信息只有当前请求能拿到
        //    之后Controller中通过UserHolder.getUser()即可获取当前登录用户
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期（只要用户一直活跃，登录状态就不断续期，不会掉线）
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        return true;
    }

    /**
     * 后置处理：请求结束（视图渲染完成后）执行
     * 作用：销毁ThreadLocal中的用户信息，避免内存泄漏
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
