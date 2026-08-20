package com.example.funeventbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 啟用 @Scheduled。目前只有 OrderExpiryScheduler 用到 ——
// 沒有這個註解的話那個排程不會報錯，只是永遠不會被觸發
@EnableScheduling
@SpringBootApplication
public class FuneventBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuneventBackendApplication.class, args);
    }

}
