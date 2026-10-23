package com.nasim.camundaai;

import com.nasim.camundaai.review.entity.Job;
import com.nasim.camundaai.review.entity.Resume;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.repository.JobRepository;
import com.nasim.camundaai.review.repository.ResumeRepository;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class StartProcessController {

    private final ZeebeClient zeebeClient;
    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeReviewRequestRepository resumeReviewRequestRepository;

    public StartProcessController(ZeebeClient zeebeClient, JobRepository jobRepository,
                                  ResumeRepository resumeRepository, ResumeReviewRequestRepository resumeReviewRequestRepository) {
        this.zeebeClient = zeebeClient;
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.resumeReviewRequestRepository = resumeReviewRequestRepository;
    }

    public record StartRequest(String resumeText, String jobDescription) {
    }
    public record StartHrReviewRequest(Long jobId, Long resumeId) {
    }

    @PostMapping("/start-review")
    public String startReview(@RequestBody StartRequest request) {
        var event = zeebeClient.newCreateInstanceCommand()
                .bpmnProcessId("resume-review-process")
                .latestVersion()
                .variables(Map.of(
                        "resumeText", request.resumeText(),
                        "jobDescription", request.jobDescription()
                ))
                .send()
                .join();

        return "Started process instance: " + event.getProcessInstanceKey();
    }
    @PostMapping("/start-hr-review")
    public String startHrReview(@RequestBody StartHrReviewRequest request) {
        Job job = jobRepository.findById(request.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + request.jobId()));
        Resume resume = resumeRepository.findById(request.resumeId())
                .orElseThrow(() -> new IllegalArgumentException("Resume not found: " + request.resumeId()));

        ProcessInstanceEvent event = zeebeClient.newCreateInstanceCommand()
                .bpmnProcessId("hr-review-with-delegation-process")
                .latestVersion()
                .variables(Map.of(
                        "resumeText", resume.getResumeText(),
                        "jobDescription", job.getDescription()
                ))
                .send()
                .join();

        resumeReviewRequestRepository.save(new ResumeReviewRequest(job, resume, event.getProcessInstanceKey(),ResumeReviewRequest.RequestStatus.OPEN));

        return "Started process instance: " + event.getProcessInstanceKey();
    }
}
