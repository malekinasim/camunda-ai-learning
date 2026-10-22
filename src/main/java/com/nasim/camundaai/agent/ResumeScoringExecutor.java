package com.nasim.camundaai.agent;

import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.service.ResumeReviewAttemptService;
import com.nasim.camundaai.review.service.ResumeReviewRequestService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Temporary executor: the scoring the poller did before, behind the interface. */
@Component
public class ResumeScoringExecutor implements AgentTaskExecutor {

    private static final String Task_HR_REVIEW_DEFINITION_ID = "Task_HrReview";
    private final ClaudeScoringService claudeScoringService;
    private final ResumeReviewRequestService resumeReviewRequestService;
    private final ResumeReviewAttemptService resumeReviewAttemptService;

    public ResumeScoringExecutor(ClaudeScoringService claudeScoringService,
                                 ResumeReviewRequestService resumeReviewRequestService,
                                 ResumeReviewAttemptService resumeReviewAttemptService) {
        this.claudeScoringService = claudeScoringService;
        this.resumeReviewRequestService = resumeReviewRequestService;
        this.resumeReviewAttemptService = resumeReviewAttemptService;
    }

    @Override
    public boolean supports(String taskDefinitionId) {
        return taskDefinitionId.equals(Task_HR_REVIEW_DEFINITION_ID);
    }

    @Override
    public AgentTaskResult execute(AgentTask task) throws Exception {
        ResumeReviewRequest request = resumeReviewRequestService.getByProcessInstanceKey(task.processInstanceKey());
        Optional<ResumeReviewAttempt> saved = resumeReviewAttemptService.find(task.id(), task.assignee());
        ResumeReviewAttempt attempt = saved.isPresent()
                ? resumeReviewAttemptService.reactivateIfWithdrawn(saved.get())
                : scoreAndSave(task, request);
        return toResult(attempt);
    }

    @Override
    public void markAsAgentCompleteTask(AgentTask current, String assignee) {
        // the poller calls this only when agent-bot completed the task (now or earlier)
        resumeReviewAttemptService.markCompleted(current.id(), assignee);
        resumeReviewRequestService.closeWithLatestCompletedAttempt(current.processInstanceKey());
    }

    @Override
    public void markAsWithdrawn(AgentTask current, String assignee) {
        resumeReviewAttemptService.markWithdrawn(current.id(), assignee);
    }

    /** Calls Claude once and saves the result before the task is completed. */
    private ResumeReviewAttempt scoreAndSave(AgentTask task, ResumeReviewRequest request) throws Exception {
        ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
                request.getResume().getResumeText(), request.getJob().getDescription());
        return resumeReviewAttemptService.saveResult(task.id(), task.assignee(), request,
                result.score(), result.decision(), result.reason());
    }

    /** Only a COMPLETED attempt means "done"; a withdrawn one was reactivated before this. */
    private AgentTaskResult toResult(ResumeReviewAttempt attempt) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("matchScore", attempt.getMatchScore().intValue());
        variables.put("verdict", attempt.getVerdict());
        variables.put("reason", attempt.getReason());
        return new AgentTaskResult(variables,
                ResumeReviewAttempt.AttemptStatus.COMPLETED.equals(attempt.getStatus()));
    }
}
