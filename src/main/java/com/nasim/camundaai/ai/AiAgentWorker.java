package com.nasim.camundaai.ai;

import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** The older Service Task (resume-review-process): the same generic agent, with a fixed skill. */
@Component
public class AiAgentWorker {

    private final AgentService agent;

    public AiAgentWorker(AgentService agent) {
        this.agent = agent;
    }

    @JobWorker(type = "call-ai-agent")
    public Map<String, Object> evaluateMatch(
            @Variable String resumeText,
            @Variable String jobDescription) throws Exception {
        // matchScore, verdict and reason, already validated against the skill's schema
        return agent.run("resume-review", List.of("hiring-policy"),
                Map.of("resumeText", resumeText, "jobDescription", jobDescription)).output();
    }
}