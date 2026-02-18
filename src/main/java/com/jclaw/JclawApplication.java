package com.jclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class,
        org.springframework.ai.autoconfigure.anthropic.AnthropicAutoConfiguration.class
})
@EnableScheduling
public class JclawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JclawApplication.class, args);
    }
}
