package com.nasim.camundaai.review.repository;


import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ResumeReviewRequestRepository extends JpaRepository<ResumeReviewRequest, Long> {

    Optional<ResumeReviewRequest> findByProcessInstanceKey(Long processInstanceKey);

}