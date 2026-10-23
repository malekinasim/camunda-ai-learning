package com.nasim.camundaai.review.repository;


import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ResumeReviewRequestRepository extends JpaRepository<ResumeReviewRequest, Long> {


    @Query(
            """
             select  rev from ResumeReviewRequest rev
             left join fetch rev.resumeReviewAttempts
                          where rev.processInstanceKey= :processInstanceKey
             """
    )
    Optional<ResumeReviewRequest> findByProcessInstanceKey(@Param(value = "processInstanceKey") Long processInstanceKey);

}