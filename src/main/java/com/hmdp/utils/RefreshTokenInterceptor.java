package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    //这里并不是自动注入，因为RefreshTokenInterceptor是我们手动在WebConfig里new出来的
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取token
        String token = request.getHeader("authorization");
        // token不存在，不需要刷新token，放行
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 从redis获取用户信息
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY+token);

        // 判断用户是否存在
        if(map.isEmpty()) {
            // 不存在，不需要刷新token，直接放行
            return true;
        }

        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

        // 存在，把用户放到ThreadLocal中
        UserHolder.saveUser(userDTO);

        // 刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 放行
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
