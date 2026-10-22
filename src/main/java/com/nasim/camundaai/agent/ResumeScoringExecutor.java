package com.nasim.camundaai.agent;

import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Temporary executor: the scoring the poller did before, behind the interface. */
@Component
public class ResumeScoringExecutor implements AgentTaskExecutor {

   private static final String Task_HR_REVIEW_DEFINITION_ID = "Task_HrReview";
    private final ClaudeScoringService claudeScoringService;
    private final ResumeReviewRequestRepository resumeReviewRequestRepository;
    private final ResumeReviewAttemptRepository resumeReviewAttemptRepository;
    public ResumeScoringExecutor(ClaudeScoringService claudeScoringService,
                                 ResumeReviewRequestRepository resumeReviewRequestRepository, ResumeReviewAttemptRepository resumeReviewAttemptRepository) {
        this.claudeScoringService = claudeScoringService;
        this.resumeReviewRequestRepository = resumeReviewRequestRepository;
        this.resumeReviewAttemptRepository = resumeReviewAttemptRepository;
    }

    @Override
    public boolean supports(String taskDefinitionId) {
        return taskDefinitionId.equals(Task_HR_REVIEW_DEFINITION_ID);
    }

    @Override
    public AgentTaskResult execute(AgentTask task) throws Exception {
    ResumeReviewRequest resumeReviewRequest = resumeReviewRequestRepository.findByProcessInstanceKey(task.processInstanceKey())
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReview found for process instance " + task.processInstanceKey()));
        Optional<ResumeReviewAttempt> reviewOpt= resumeReviewAttemptRepository.findByTaskIdAndReviewer(task.id(),task.assignee());
         ResumeReviewAttempt resumeReviewAttempt;
        if ( reviewOpt.isEmpty()) {

            ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
                    resumeReviewRequest.getResume().getResumeText(), resumeReviewRequest.getJob().getDescription());
            resumeReviewAttempt =new ResumeReviewAttempt(
                    task.id(),
                    result.reason(),
                    result.decision(),
                    (double) result.score(),
                    task.assignee(),
                    resumeReviewRequest);
                resumeReviewAttempt = resumeReviewAttemptRepository.save(resumeReviewAttempt);

        }else resumeReviewAttempt =reviewOpt.get();

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("matchScore", resumeReviewAttempt.getMatchScore().intValue());
        variables.put("verdict", resumeReviewAttempt.getVerdict());
        variables.put("reason", resumeReviewAttempt.getReason());
        return new AgentTaskResult(variables, resumeReviewAttempt.isAgentCompletionConfirmed());
    }
    @Override
    public void markAsAgentCompleteTask(AgentTask current, String assignee) {
        ResumeReviewAttempt reviewAttempt = resumeReviewAttemptRepository.findByTaskIdAndReviewer(current.id(),assignee)
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReviewAttempt found for task id " + current.id()));

        if (TaskState.COMPLETED.name().equals(current.state())) {
            reviewAttempt.setAgentCompletionConfirmed(true);
            resumeReviewAttemptRepository.save(reviewAttempt);
            return;
        }
        if (!TaskState.CREATED.name().equals(current.state()) || !assignee.equals(current.assignee())) {
            return;
        }
        reviewAttempt.setAgentCompletionConfirmed(true);
        resumeReviewAttemptRepository.save(reviewAttempt);
    }

}