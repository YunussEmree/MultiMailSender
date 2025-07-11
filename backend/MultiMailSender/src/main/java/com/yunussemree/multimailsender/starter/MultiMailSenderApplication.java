package com.yunussemree.multimailsender.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.yunussemree.multimailsender")
public class MultiMailSenderApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiMailSenderApplication.class, args);
    }

}
