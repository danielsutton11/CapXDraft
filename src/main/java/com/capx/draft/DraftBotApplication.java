package com.capx.draft;

import com.capx.draft.config.BotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BotProperties.class)
public class DraftBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(DraftBotApplication.class, args);
    }
}
