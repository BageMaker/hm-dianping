package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
// import jdk.vm.ci.meta.Local;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testSaveShop() throws InterruptedException {
        // shopService.saveShopToRedis(1L, 10L);
    }

    @Test
    void getTime() {
        System.out.println(System.currentTimeMillis());
    }
}
