package com.nasim.camundaai.review.service;

import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt.AttemptStatus;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/** Lifecycle of one review attempt: RESULT_SAVED -> COMPLETED, or RESULT_SAVED -> WITHDRAW (-> RESULT_SAVED on hand-back). */
@Service
public class ResumeReviewAttemptService {

    private final ResumeReviewAttemptRepository resumeReviewAttemptRepository;

    public ResumeReviewAttemptService(ResumeReviewAttemptRepository resumeReviewAttemptRepository) {
        this.resumeReviewAttemptRepository = resumeReviewAttemptRepository;
    }

    public Optional<ResumeReviewAttempt> find(String taskId, String reviewer) {
        return resumeReviewAttemptRepository.findByTaskIdAndReviewer(taskId, reviewer);
    }

    /** Saves a result before the task is completed, so a retry can reuse it. */
    public ResumeReviewAttempt saveResult(String taskId, String reviewer, ResumeReviewRequest request,
                                          double matchScore, String verdict, String reason) {
        ResumeReviewAttempt attempt = new ResumeReviewAttempt(
                taskId, reason, verdict, matchScore, reviewer, request, AttemptStatus.RESULT_SAVED);
        return resumeReviewAttemptRepository.save(attempt);
    }

    /** The task was handed back to the same reviewer: make a withdrawn attempt usable again. */
    public ResumeReviewAttempt reactivateIfWithdrawn(ResumeReviewAttempt attempt) {
        if (!AttemptStatus.WITHDRAWN.equals(attempt.getStatus())) {
            return attempt;
        }
        attempt.setStatus(AttemptStatus.RESULT_SAVED);
        attempt.setCompletedAt(null);
        return resumeReviewAttemptRepository.save(attempt);
    }

    public ResumeReviewAttempt reactivateIfWithdrawn(String  taskId,String assignee) {
      ResumeReviewAttempt attempt=  this.find(taskId, assignee).orElseThrow(
              ()->new IllegalStateException(  "No ResumeReviewAttempt found for task id " + taskId)
          );

        if (!AttemptStatus.WITHDRAWN.equals(attempt.getStatus())) {
            return attempt;
        }
        attempt.setStatus(AttemptStatus.RESULT_SAVED);
        attempt.setCompletedAt(null);
        return resumeReviewAttemptRepository.save(attempt);
    }
    public void markCompleted(String taskId, String reviewer) {
        ResumeReviewAttempt attempt = find(taskId, reviewer)
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReviewAttempt found for task id " + taskId));
        if (AttemptStatus.COMPLETED.equals(attempt.getStatus())) {
            return;   // already completed; keep the original completedAt
        }
        attempt.setCompletedAt(LocalDateTime.now());
        attempt.setStatus(AttemptStatus.COMPLETED);
        resumeReviewAttemptRepository.save(attempt);
    }

    /** Only a saved, not yet applied result can be withdrawn; a COMPLETED attempt is never touched. */
    public void markWithdrawn(String taskId, String reviewer) {
        find(taskId, reviewer)
                .filter(attempt -> AttemptStatus.RESULT_SAVED.equals(attempt.getStatus()))
                .ifPresent(attempt -> {
                    attempt.setCompletedAt(LocalDateTime.now());
                    attempt.setStatus(AttemptStatus.WITHDRAWN);
                    resumeReviewAttemptRepository.save(attempt);
                });
    }
}
