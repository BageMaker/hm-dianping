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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 检验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式有误");
        }
        // 手机号格式正确，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // session保存验证码到redis
        // session.setAttribute("code", code);
        // 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码 模拟发送 使用Slf4j日志输出
        log.debug("发送验证码成功，验证码为:{}", code);
        //返回成功信息
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 获取手机号
        String phone = loginForm.getPhone();
        // 检验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式有误");
        }
        // 校验 验证码
        // 获取 session中的验证码
//        Object sessionCode = session.getAttribute("code");
        // 获取登录的验证码
//        String code = loginForm.getCode();
//        if(sessionCode == null || !sessionCode.toString().equals(code)) {
//            // 验证码失效或有误
//            return Result.fail("验证码失效或有误");
//        }
        // 从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        // 获取登录的验证码
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("手机号格式有误");
        }
        // 通过手机号查询用户是否存在 select * from tb_user where phone = ?
        // 使用mybatis-plus
        User user = query().eq("phone", phone).one();
        // 用户不存在则创建
        if(user == null) {
            // 根据手机号创建用户并保存
            user = createUserWithPhone(phone);
        }
        // 用户信息存到session，暴露用户敏感信息
        // session.setAttribute("user", user);
        // 存储UserDTO 到 session
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 保存用户信息到Redis
        // 去除敏感用户信息，存到userDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 这里将UserDTO中的属性转为string类型的
        // setFieldValueEditor 将属性值转换为String类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName, fieldValue)->fieldValue.toString())
        );
        // 生成随机token
        String token = UUID.randomUUID().toString();
        // 将用户信息放到redis中
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token, userMap);
        //设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        // mybatis-plus 保存用户
        save(user);
        return user;
    }
}
