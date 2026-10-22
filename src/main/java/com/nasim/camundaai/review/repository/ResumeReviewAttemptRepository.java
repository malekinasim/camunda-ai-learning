package com.nasim.camundaai.review.repository;

import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeReviewAttemptRepository extends JpaRepository<ResumeReviewAttempt,Long> {

    Optional<ResumeReviewAttempt> findByTaskIdAndReviewer(String taskId, String reviewer);
}
