package world.willfrog.datafrog.server;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo
// 014-data-06：热配置桥的定时整链重拉（30s）与运行时热配置的文件轮询（10s）都靠调度驱动
@EnableScheduling
public class DataFrogServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataFrogServerApplication.class, args);
    }
}
