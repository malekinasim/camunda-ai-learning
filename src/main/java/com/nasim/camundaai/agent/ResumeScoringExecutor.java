package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.AgentTask;
import com.nasim.camundaai.agent.AgentTaskExecutor;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Temporary executor: the scoring the poller did before, behind the interface. */
@Component
public class ResumeScoringExecutor implements AgentTaskExecutor {

   private static final String Task_HR_REVIEW_DEFINITION_ID = "Task_HrReview";
    private final ClaudeScoringService claudeScoringService;
    private final ResumeReviewRepository resumeReviewRepository;

    public ResumeScoringExecutor(ClaudeScoringService claudeScoringService, ResumeReviewRepository resumeReviewRepository) {
        this.claudeScoringService = claudeScoringService;
        this.resumeReviewRepository = resumeReviewRepository;
    }

    @Override
    public boolean supports(String taskType) {
        return taskType.equals(Task_HR_REVIEW_DEFINITION_ID);
    }

    @Override
    public Map<String, Object> execute(AgentTask task) throws Exception {
    ResumeReview review = resumeReviewRepository.findByProcessInstanceKey(task.processInstanceKey())
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReview found for process instance " + task.processInstanceKey()));


        if (task.id().equals(review.getAgentTaskId()) && !review.isAgentCompletionConfirmed()) {

            ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
                    review.getResume().getResumeText(), review.getJob().getDescription());
            review.setMatchScore((double) result.score());
            review.setVerdict(result.decision());
            review.setReason(result.reason());
            review.setAgentTaskId(task.id());
            review.setAgentCompletionConfirmed(false);
            resumeReviewRepository.save(review);

        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("matchScore", review.getMatchScore());
        variables.put("verdict", review.getVerdict());
        variables.put("reason", review.getReason());
        variables.put("isComplete", review.isAgentCompletionConfirmed());
        return variables;
    }
    @Override
    public void completeTask(AgentTask current,String assignee) {
        ResumeReview review = resumeReviewRepository.findByProcessInstanceKey(current.processInstanceKey())
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReview found for process instance " + current.processInstanceKey()));

        if (TaskState.COMPLETED.name().equals(current.state())) {
            review.setAgentCompletionConfirmed(true);
            resumeReviewRepository.save(review);
            return;
        }
        if (!TaskState.CREATED.name().equals(current.state()) || !assignee.equals(current.assignee())) {
            return;
        }
        review.setAgentCompletionConfirmed(true);
        resumeReviewRepository.save(review);
    }

}