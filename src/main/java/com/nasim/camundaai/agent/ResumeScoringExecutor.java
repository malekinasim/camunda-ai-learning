package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.AgentTask;
import com.nasim.camundaai.agent.AgentTaskExecutor;
import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Temporary executor: the scoring the poller did before, behind the interface. */
@Component
public class ResumeScoringExecutor implements AgentTaskExecutor {


    private final ClaudeScoringService claudeScoringService;

    public ResumeScoringExecutor(ClaudeScoringService claudeScoringService) {
        this.claudeScoringService = claudeScoringService;
    }

    @Override
    public Map<String, Object> execute(AgentTask task,Map<String,Object> attributes) throws Exception {
        String resumeText= String.valueOf(attributes.get("resume-txt"));
        String jobDescription= String.valueOf(attributes.get("job-description"));
        ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
               resumeText,jobDescription);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("matchScore", result.score());
        output.put("verdict", result.decision());
        output.put("reason", result.reason());
        return output;
    }
}