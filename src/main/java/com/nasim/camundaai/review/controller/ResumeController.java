package com.nasim.camundaai.review.controller;

import com.nasim.camundaai.review.entity.Resume;
import com.nasim.camundaai.review.repository.ResumeRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResumeController {

    private final ResumeRepository resumeRepository;

    public ResumeController(ResumeRepository resumeRepository) {
        this.resumeRepository = resumeRepository;
    }

    public record CreateResumeRequest(String candidateName, String resumeText) {
    }

    @PostMapping("/resumes")
    public Resume createResume(@RequestBody CreateResumeRequest request) {
        return resumeRepository.save(new Resume(request.candidateName(), request.resumeText()));
    }
}