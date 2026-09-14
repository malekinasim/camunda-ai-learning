package com.nasim.camundaai.review.repository;

import com.nasim.camundaai.review.entity.ResumeReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeReviewRepository extends JpaRepository<ResumeReview, Long> {

    Optional<ResumeReview> findByProcessInstanceKey(Long processInstanceKey);
}