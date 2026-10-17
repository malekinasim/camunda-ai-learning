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

/**
 * Generic start execution listener for any user task that supports delegation.
 *
 * The task itself declares who it belongs to, through input mappings in the BPMN:
 *   taskType        - business type of the task, matched against delegation rules
 *   delegatorRole   - role that normally owns the task and may delegate it
 *   defaultAssignee - who gets the task when no delegation is active
 *
 * Nothing about a specific process lives in this class, so a new user task only
 * needs the three inputs and this listener in the modeler - no new Java code.
 */
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
        // ActivatedJob tells us which element triggered the listener. We only use it
        // for error messages: routing is driven by the declared inputs, not by the id.
        String where = job.getBpmnProcessId() + "/" + job.getElementId();
        requireDeclared("taskType", taskType, where);
        requireDeclared("delegatorRole", delegatorRole, where);
        requireDeclared("defaultAssignee", defaultAssignee, where);

        Optional<TaskDelegation> activeDelegation = selectDelegation(taskType,
                repository.findActiveDelegations(delegatorRole, taskType, LocalDate.now()));

        Map<String, Object> decisionInput = new HashMap<>();
        decisionInput.put("defaultAssignee", defaultAssignee);
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

    /**
     * Fails loudly when a task uses this listener without declaring an input.
     * Falling back to a default would silently send the task to the wrong person.
     */
    static void requireDeclared(String name, String value, String where) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("User task " + where + " uses the '" + JOB_TYPE
                    + "' listener but does not declare input '" + name + "'");
        }
    }

    /**
     * A rule for this exact task type wins over general rules (taskType null or 'ALL').
     * Two rules at the same level are a configuration error, not something to guess about.
     */
    static Optional<TaskDelegation> selectDelegation(String taskType, List<TaskDelegation> candidates) {
        List<TaskDelegation> specific = candidates.stream()
                .filter(d -> taskType.equals(d.getTaskType())).toList();
        List<TaskDelegation> selected = specific.isEmpty() ? candidates : specific;
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
