package com.nasim.camundaai.agent;

import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import org.springframework.stereotype.Component;

import javax.xml.crypto.Data;
import java.time.LocalDateTime;
import java.util.Comparator;
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
                    resumeReviewRequest,ResumeReviewAttempt.AttemptStatus.RESULT_SAVED);
                resumeReviewAttempt = resumeReviewAttemptRepository.save(resumeReviewAttempt);

        } else {
            resumeReviewAttempt = reviewOpt.get();
            if (ResumeReviewAttempt.AttemptStatus.WITHDRAW.equals(resumeReviewAttempt.getStatus())) {
                // the task was handed back to the agent; reuse the saved result instead of calling Claude again
                resumeReviewAttempt.setStatus(ResumeReviewAttempt.AttemptStatus.RESULT_SAVED);
                resumeReviewAttempt.setCompletedAt(null);
                resumeReviewAttempt = resumeReviewAttemptRepository.save(resumeReviewAttempt);
            }
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("matchScore", resumeReviewAttempt.getMatchScore().intValue());
        variables.put("verdict", resumeReviewAttempt.getVerdict());
        variables.put("reason", resumeReviewAttempt.getReason());
        // only a COMPLETED attempt means "done"; a withdrawn one was reactivated above
        return new AgentTaskResult(variables, ResumeReviewAttempt.AttemptStatus.COMPLETED.equals(resumeReviewAttempt.getStatus()));
    }

    @Override
    public void markAsWithdrawn(AgentTask current, String assignee) {
        Optional<ResumeReviewAttempt> reviewAttempt = resumeReviewAttemptRepository.findByTaskIdAndReviewer(current.id(), assignee);
        // only a saved, not yet applied result can be withdrawn; never touch a COMPLETED attempt
        if (reviewAttempt.isPresent()
                && ResumeReviewAttempt.AttemptStatus.RESULT_SAVED.equals(reviewAttempt.get().getStatus())) {
            reviewAttempt.get().setCompletedAt(LocalDateTime.now());
            reviewAttempt.get().setStatus(ResumeReviewAttempt.AttemptStatus.WITHDRAW);
            resumeReviewAttemptRepository.save(reviewAttempt.get());
        }
    }
    @Override
    public void markAsAgentCompleteTask(AgentTask current, String assignee) {
        ResumeReviewAttempt reviewAttempt = resumeReviewAttemptRepository.findByTaskIdAndReviewer(current.id(),assignee)
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReviewAttempt found for task id " + current.id()));

        // the poller calls this only when agent-bot completed the task (now or earlier);
        // all "not applied" cases go to markAsWithdrawn
        reviewAttempt.setCompletedAt(LocalDateTime.now());
        reviewAttempt.setStatus(ResumeReviewAttempt.AttemptStatus.COMPLETED);
        resumeReviewAttemptRepository.save(reviewAttempt);


       Optional<ResumeReviewRequest> resumeReviewRequest= resumeReviewRequestRepository.findByProcessInstanceKey(current.processInstanceKey());
      if(resumeReviewRequest.isPresent() && !resumeReviewRequest.get().getResumeReviewAttempts().isEmpty()) {
          Optional<ResumeReviewAttempt> finalAttempt = resumeReviewRequest.get().getResumeReviewAttempts().stream()
                  .filter(r ->
                          ResumeReviewAttempt.AttemptStatus.COMPLETED.equals(r.getStatus()))
                  .max(Comparator.comparing(ResumeReviewAttempt::getCompletedAt));
          if(finalAttempt.isPresent()) {
              resumeReviewRequest.get().setFinalReviewAttempt(finalAttempt.get());
              resumeReviewRequest.get().setStatus(ResumeReviewRequest.RequestStatus.COMPLETED);
              resumeReviewRequestRepository.save(resumeReviewRequest.get());
          }
      }

    }

}