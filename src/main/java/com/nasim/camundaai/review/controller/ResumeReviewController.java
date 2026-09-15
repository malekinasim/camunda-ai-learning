package com.nasim.camundaai.review.controller;

import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ResumeReviewController {

    private final ResumeReviewRepository resumeReviewRepository;

    public ResumeReviewController(ResumeReviewRepository resumeReviewRepository) {
        this.resumeReviewRepository = resumeReviewRepository;
    }

    @GetMapping("/resume-reviews")
    public List<ResumeReview> listResumeReviews() {
        return resumeReviewRepository.findAll();
    }
}