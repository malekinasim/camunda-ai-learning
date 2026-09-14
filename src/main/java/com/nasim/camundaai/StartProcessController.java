package com.nasim.camundaai;

import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static com.fasterxml.jackson.databind.type.LogicalType.Map;

@RestController
public class StartProcessController {

    private final ZeebeClient zeebeClient;

    public StartProcessController(ZeebeClient zeebeClient) {
        this.zeebeClient = zeebeClient;
    }

    public record StartRequest(String resumeText, String jobDescription) {
    }

    /**
     * Test with:
     * curl -X POST http://localhost:9090/start-review \
     * -H "Content-Type: application/json" \
     * -d '{"resumeText": "...", "jobDescription": "..."}'
     * <p>
     * This starts a new instance of resume-review-process with the two
     * variables our AiAgentWorker needs. Watch it happen live in Operate
     * at http://localhost:8081.
     */
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
}
