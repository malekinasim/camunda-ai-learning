package com.nasim.camundaai.delegation.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nasim.camundaai.delegation.entity.TaskDelegation;
import com.nasim.camundaai.delegation.repository.TaskDelegationRepository;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.EvaluateDecisionResponse;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ResolveAssigneeListener {

    static final String JOB_TYPE = "resolve-assignee";
    static final List<String> REQUIRED_INPUTS = List.of("taskType", "delegatorRole", "defaultAssignee");

    private final TaskDelegationRepository repository;
    private final ZeebeClient zeebeClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ResolveAssigneeListener(TaskDelegationRepository repository, ZeebeClient zeebeClient) {
        this.repository = repository;
        this.zeebeClient = zeebeClient;
    }

    @JobWorker(type = JOB_TYPE)
    public Map<String, Object> resolveAssignee(ActivatedJob job,
                                               @Variable String taskType,
                                               @Variable String delegatorRole,
                                               @Variable String defaultAssignee) throws Exception {

        String where = job.getBpmnProcessId() + "/" + job.getElementId();
        requireDeclared("taskType", taskType, where);
        requireDeclared("delegatorRole", delegatorRole, where);
        requireDeclared("defaultAssignee", defaultAssignee, where);

        Optional<TaskDelegation> activeDelegation = selectDelegation(taskType,
                repository.findActiveDelegations(delegatorRole, taskType, LocalDate.now()));

        //TODO we should think about DMN generality more that cover all forms of assignee
        Map<String, Object> decisionInput = new HashMap<>();
        decisionInput.put("defaultAssignee", defaultAssignee);
        decisionInput.put("hasActiveDelegation", activeDelegation.isPresent());
        decisionInput.put("taskType", activeDelegation.map(TaskDelegation::getTaskType).orElse(""));
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

    static void requireDeclared(String name, String value, String where) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("User task " + where + " uses the '" + JOB_TYPE
                    + "' listener but does not declare input '" + name + "'");
        }
    }

    static Optional<TaskDelegation> selectDelegation(String taskType, List<TaskDelegation> candidates) {
        List<TaskDelegation> specific = candidates.stream()
                .filter(d -> taskType.equals(d.getTaskType())).toList();
        List<TaskDelegation> selected = specific.isEmpty() ? candidates : specific;
        //TODO  we can manage this conflict of delegate better maybe they are overlap not conflict and we can replace them by their union of them
        if (selected.size() > 1) {
            throw new IllegalStateException("Overlapping active delegation rules at the same priority for task type '"
                    + taskType + "'");
        }
        Optional<TaskDelegation> result = selected.stream().findFirst();
        if (result.isPresent() && (result.get().getDelegateTo() == null
                || result.get().getDelegateTo().isBlank())) {
            throw new IllegalStateException("Delegation target must not be blank");
        }
        return result;
    }
}
