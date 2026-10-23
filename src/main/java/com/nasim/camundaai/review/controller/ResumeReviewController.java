package com.nasim.camundaai.review.controller;

import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ResumeReviewController {

    private final ResumeReviewRequestRepository resumeReviewRequestRepository;

    public ResumeReviewController(ResumeReviewRequestRepository resumeReviewRequestRepository) {
        this.resumeReviewRequestRepository = resumeReviewRequestRepository;
    }


    @GetMapping("/resume-reviews")
    public List<ResumeReviewRequest> listResumeReviews() {
        return resumeReviewRequestRepository.findAll();
    }
}