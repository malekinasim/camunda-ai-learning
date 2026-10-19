package com.nasim.camundaai.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class AgentTaskExecutors {               // Spring injects every AgentTaskExecutor bean
    private final List<AgentTaskExecutor> all;
    public AgentTaskExecutors(List<AgentTaskExecutor> all) { this.all = all; }
    public Optional<AgentTaskExecutor> forType(String taskDefinitionId) {
        return all.stream().filter(e -> e.supports(taskDefinitionId)).findFirst();
    }
}
