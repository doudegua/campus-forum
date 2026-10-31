package net.doudegua;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 * <p>
 * {@code @EnableScheduling} 是给 {@code LikeCountFlusher} 用的：没有它，
 * 那个带 {@code @Scheduled} 的方法只是一个普通方法，永远不会被容器调用。
 */
@EnableScheduling
@SpringBootApplication
public class MyProjectBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyProjectBackendApplication.class, args);
    }

}
