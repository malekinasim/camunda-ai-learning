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
import java.util.List;

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
                selectDelegation(repository.findActiveDelegations(DELEGATOR_ROLE, TASK_TYPE, LocalDate.now()));

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
    static Optional<TaskDelegation> selectDelegation(List<TaskDelegation> candidates) {
        List<TaskDelegation> specific = candidates.stream()
                .filter(d -> TASK_TYPE.equals(d.getTaskType())).toList();
        List<TaskDelegation> selected = specific.isEmpty() ? candidates : specific;
        if (selected.size() > 1) {
            throw new IllegalStateException("Overlapping active delegation rules at the same priority");
        }
        Optional<TaskDelegation> result = selected.stream().findFirst();
        if (result.isPresent() && (result.get().getDelegateTo() == null
                || result.get().getDelegateTo().isBlank())) {
            throw new IllegalStateException("Delegation target must not be blank");
        }
        return result;
    }
}
