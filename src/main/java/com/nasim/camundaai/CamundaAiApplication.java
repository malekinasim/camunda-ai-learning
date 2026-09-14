package com.nasim.camundaai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CamundaAiApplication {

    public static void main(String[] args) {
        // Starting this app does three things automatically (thanks to the
        // Camunda Spring Boot Starter):
        //   1. Connects to the Zeebe broker (see application.yaml)
        //   2. Deploys any *.bpmn files found on the classpath (resume-review.bpmn)
        //   3. Registers any @JobWorker-annotated methods (see AiAgentWorker)
        SpringApplication.run(CamundaAiApplication.class, args);
    }
}
