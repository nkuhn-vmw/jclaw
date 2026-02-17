package com.jclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JclawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JclawApplication.class, args);
    }
}
