package world.willfrog.datafrog.server;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
public class DataFrogServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataFrogServerApplication.class, args);
    }
}
