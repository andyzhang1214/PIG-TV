package com.pigtv.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.pigtv.server.config.AppProps;

@SpringBootApplication
@EnableConfigurationProperties(AppProps.class)
public class PigTvServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PigTvServerApplication.class, args);
    }
}
