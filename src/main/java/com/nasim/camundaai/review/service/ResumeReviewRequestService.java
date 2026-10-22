package com.nasim.camundaai.review.service;

import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Optional;

@Service
public class ResumeReviewRequestService {

    private final ResumeReviewRequestRepository resumeReviewRequestRepository;

    public ResumeReviewRequestService(ResumeReviewRequestRepository resumeReviewRequestRepository) {
        this.resumeReviewRequestRepository = resumeReviewRequestRepository;
    }

    public ResumeReviewRequest getByProcessInstanceKey(Long processInstanceKey) {
        return resumeReviewRequestRepository.findByProcessInstanceKey(processInstanceKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReviewRequest found for process instance " + processInstanceKey));
    }

    /**
     * Sets the latest COMPLETED attempt as the final one and closes the request.
     * Kept here for now; moves with the completion logic in #30 / #31 / #24.
     */
    public void closeWithLatestCompletedAttempt(Long processInstanceKey) {
        Optional<ResumeReviewRequest> request = resumeReviewRequestRepository.findByProcessInstanceKey(processInstanceKey);
        if (request.isEmpty() || request.get().getResumeReviewAttempts().isEmpty()) {
            return;
        }
        request.get().getResumeReviewAttempts().stream()
                .filter(attempt -> ResumeReviewAttempt.AttemptStatus.COMPLETED.equals(attempt.getStatus()))
                .max(Comparator.comparing(ResumeReviewAttempt::getCompletedAt))
                .ifPresent(finalAttempt -> {
                    request.get().setFinalReviewAttempt(finalAttempt);
                    request.get().setStatus(ResumeReviewRequest.RequestStatus.COMPLETED);
                    resumeReviewRequestRepository.save(request.get());
                });
    }
}
