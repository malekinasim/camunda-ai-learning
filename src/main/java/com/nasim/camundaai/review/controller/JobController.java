package com.nasim.camundaai.review.controller;

import com.nasim.camundaai.review.entity.Job;
import com.nasim.camundaai.review.repository.JobRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {

    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public record CreateJobRequest(String title, String description) {
    }

    @PostMapping("/jobs")
    public Job createJob(@RequestBody CreateJobRequest request) {
        return jobRepository.save(new Job(request.title(), request.description()));
    }
}