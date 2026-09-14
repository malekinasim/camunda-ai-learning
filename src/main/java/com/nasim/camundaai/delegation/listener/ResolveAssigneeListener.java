package com.nasim.camundaai.delegation.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nasim.camundaai.delegation.entity.TaskDelegation;
import com.nasim.camundaai.delegation.repository.TaskDelegationRepository;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.EvaluateDecisionResponse;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ResolveAssigneeListener {

    private static final String DELEGATOR_ROLE = "HR_EMPLOYEE";
    private static final String TASK_TYPE = "hr-review";
    private static final String DEFAULT_ASSIGNEE = "hr-employee";

    private final TaskDelegationRepository repository;
    private final ZeebeClient zeebeClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ResolveAssigneeListener(TaskDelegationRepository repository, ZeebeClient zeebeClient) {
        this.repository = repository;
        this.zeebeClient = zeebeClient;
    }

    @JobWorker(type = "resolve-hr-assignee")
    public Map<String, Object> resolveAssignee() throws Exception {

        Optional<TaskDelegation> activeDelegation =
                repository.findActiveDelegation(DELEGATOR_ROLE, TASK_TYPE, LocalDate.now());

        Map<String, Object> decisionInput = new HashMap<>();
        decisionInput.put("defaultAssignee", DEFAULT_ASSIGNEE);
        decisionInput.put("hasActiveDelegation", activeDelegation.isPresent());
        decisionInput.put("delegateTo", activeDelegation.map(TaskDelegation::getDelegateTo).orElse(""));

        EvaluateDecisionResponse decision = zeebeClient.newEvaluateDecisionCommand()
                .decisionId("resolve-assignee")
                .variables(mapper.writeValueAsString(decisionInput))
                .send()
                .join();

        String finalAssignee = mapper.readValue(decision.getDecisionOutput(), String.class);

        Map<String, Object> output = new HashMap<>();
        output.put("finalAssignee", finalAssignee);
        return output;
    }
}