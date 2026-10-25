package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        // 3.符合，使用hutool生成6位数字验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis，key = "login:code:" + 手机号，有效期LOGIN_CODE_TTL(2分钟)
        //   作用：登录时从redis中取出验证码与用户输入的对比，保证验证码是刚才发送的且未过期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.真实项目这里要调用第三方短信平台发送短信，课程中简化处理：
        //   只把验证码打印到控制台（IDEA控制台可查看），方便测试
        log.debug("发送短信验证码成功，验证码：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        // 3.从redis获取之前保存的验证码并校验
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();

        // 4.判断验证码是否一致（redis中查不到说明验证码过期或没发送过）
        if (cachecode==null || ! cachecode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        // 5.验证码一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        // 6.判断用户是否存在
        if(user==null){
            // 7.不存在，创建新用户并保存到数据库
            user = createUserWithPhone(phone);
        }
        // 8.保存用户信息到redis，实现登录功能
        // 8.1.随机生成token，作为登录令牌（相当于session id，前端每次请求携带它证明"我是谁"）
        String token = UUID.randomUUID().toString();
        // 8.2.将User对象转为UserDTO（去掉敏感字段，如密码、手机号），再转成HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 注意：StringRedisTemplate要求redis中存的全是String类型，
        // 而UserDTO的id是Long类型，直接putAll会报序列化异常！
        // 所以必须用CopyOptions把每个字段的值统一转成String再存
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)           // 忽略null值的字段，减少无用数据
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 8.3.存储用户信息到redis的Hash结构，key = "login:token:" + token
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 8.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 9.把token返回给前端：前端会保存到localStorage，
        //    之后的请求都放在请求头authorization中携带，登录拦截器根据它放行
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();

    }

    @Override
    public Result signCount() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截至今天为止的所有签到记录，返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result==null||result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        //6.循环遍历
        int count =0;
        while (true){
            //7.让这个数字与1做与运算，得到数字的最后一个比特位 判断这个bit位是否为0
            if((num & 1)==0){
                //如果为0，说明未签到，结束
                break;
            }else{
                //如果不为0，说明已经签到，计数器加一
                count++;
            }
            //数字右移一位，抛弃最后一个bit位
            num>>>=1;
        }
        return Result.ok(count);
    }

    /**
     * 根据手机号创建新用户并保存到数据库
     * @param phone 手机号
     * @return 新创建的用户
     */
    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        // 2.设置随机昵称：user_ + 10位随机字符串
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        // 3.保存用户到数据库（insert into tb_user ...）
        save(user);
        return user;
    }
}
