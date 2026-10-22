package com.nasim.camundaai.review.repository;


import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ResumeReviewRequestRepository extends JpaRepository<ResumeReviewRequest, Long> {

    @Query(
            """
             select rev from ResumeReviewRequest rev
             join fetch rev.resumeReviewAttempts
             """
    )
    Optional<ResumeReviewRequest> findByProcessInstanceKey(Long processInstanceKey);

}