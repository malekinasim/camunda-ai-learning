package com.nasim.camundaai;

import io.camunda.zeebe.spring.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Deployment(resources = {"classpath*:*.bpmn", "classpath*:*.dmn", "classpath*:*.form"})
public class CamundaAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamundaAiApplication.class, args);
    }
}