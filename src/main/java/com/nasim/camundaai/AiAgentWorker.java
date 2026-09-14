package com.nasim.camundaai;

import com.nasim.camundaai.ai.ClaudeScoringService;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class AiAgentWorker {

    private final ClaudeScoringService claudeScoringService;

    public AiAgentWorker(ClaudeScoringService claudeScoringService) {
        this.claudeScoringService = claudeScoringService;
    }

    @JobWorker(type = "call-ai-agent")
    public Map<String, Object> evaluateMatch(
            @Variable String resumeText,
            @Variable String jobDescription) throws Exception {

        ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(resumeText, jobDescription);

        Map<String, Object> outputVariables = new HashMap<>();
        outputVariables.put("matchScore", result.score());
        outputVariables.put("verdict", result.verdict());
        return outputVariables;
    }
}