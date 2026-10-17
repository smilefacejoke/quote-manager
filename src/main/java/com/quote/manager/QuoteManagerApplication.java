package com.quote.manager;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.quote.manager.mapper")
public class QuoteManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuoteManagerApplication.class, args);
    }
}
