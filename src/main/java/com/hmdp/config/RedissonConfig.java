package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加redis地址，这里添加单点的地址，也可以使用config.useClusterServicves()添加集群地址
        config.useSingleServer().setAddress("resid://127.0.0.1:6379").setPassword("12321");
        // 创建客户端
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2() {
        // 配置类
        Config config = new Config();
        // 添加redis地址，这里添加单点的地址，也可以使用config.useClusterServicves()添加集群地址
        config.useSingleServer().setAddress("resid://127.0.0.1:6380").setPassword("12321");
        // 创建客户端
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient3() {
        // 配置类
        Config config = new Config();
        // 添加redis地址，这里添加单点的地址，也可以使用config.useClusterServicves()添加集群地址
        config.useSingleServer().setAddress("resid://127.0.0.1:6381").setPassword("12321");
        // 创建客户端
        return Redisson.create(config);
    }
}
