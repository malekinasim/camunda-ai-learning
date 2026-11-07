package com.nasim.camundaai.gent.assignee.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nasim.camundaai.gent.assignee.decisions.managment.DecisionUtils;     // added
import com.nasim.camundaai.gent.assignee.entity.AutonomyLevel;                  // added
import com.nasim.camundaai.gent.assignee.entity.TaskDelegation;
import com.nasim.camundaai.gent.assignee.repository.TaskDelegationRepository;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.stereotype.Component;
// removed: JsonProcessingException, EvaluateDecisionResponse, micrometer StringUtils, netty StringUtil (no longer used)

import java.time.LocalDate;
import java.util.*;

@Component
public class ResolveAssigneeListener {

    static final String JOB_TYPE = "resolve-assignee";
    static final String AGENT_ASSIGNEE = "agent-bot";                           // added
    static final List<String> REQUIRED_INPUTS = List.of("taskType", "delegatorRole", "defaultAssignee");

    private final TaskDelegationRepository repository;
    private final ZeebeClient zeebeClient;
    private final DecisionUtils decisionUtils;                                  // added
    private final ObjectMapper mapper = new ObjectMapper();

    public ResolveAssigneeListener(TaskDelegationRepository repository, ZeebeClient zeebeClient,
                                   DecisionUtils decisionUtils) {               // changed
        this.repository = repository;
        this.zeebeClient = zeebeClient;
        this.decisionUtils = decisionUtils;                                     // added
    }

    @JobWorker(type = JOB_TYPE, fetchAllVariables = true)                      // changed: the DMNs need all task variables
    public Map<String, Object> resolveAssignee(ActivatedJob job,
                                               @Variable String taskType,
                                               @Variable String delegatorRole,
                                               @Variable String defaultAssignee,
                                               @Variable String taskAssigneeDecisions) throws Exception {

        String where = job.getBpmnProcessId() + "/" + job.getElementId();
        requireDeclared("taskType", taskType, where);
        requireDeclared("delegatorRole", delegatorRole, where);
        requireDeclared("defaultAssignee", defaultAssignee, where);

        // 1. Facts from the database (not a decision): did the owner ask for the agent?
        Optional<TaskDelegation> activeDelegation = selectDelegation(taskType,
                repository.findActiveDelegations(delegatorRole, taskType, LocalDate.now()));

        // 2. All task variables, plus the facts the rules need                // added
        Map<String, Object> variables = new HashMap<>(job.getVariablesAsMap());
        variables.put("agentRequested", activeDelegation
                .map(d -> AGENT_ASSIGNEE.equals(d.getDelegateTo()))
                .orElse(false));

        // 3. All rules of this task together; the strictest wins             // added
        AutonomyLevel autonomyBefore = decisionUtils.makeFinalDecision(zeebeClient, mapper,
                taskAssigneeDecisions, mapper.writeValueAsString(variables));

        // 4. The assignee follows from the autonomy level                    // changed: replaces the resolve-assignee DMN
        String finalAssignee = autonomyBefore == AutonomyLevel.HUMAN
                ? defaultAssignee
                : AGENT_ASSIGNEE;

        Map<String, Object> output = new HashMap<>();
        output.put("finalAssignee", finalAssignee);
        output.put("autonomyBefore", autonomyBefore.name());                   // added: the poller combines it with the after-checks
        return output;
    }

    // removed: decisionTaskType(...) — it only prepared input for resolve-assignee.dmn

    static void requireDeclared(String name, String value, String where) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("User task " + where + " uses the '" + JOB_TYPE
                    + "' listener but does not declare input '" + name + "'");
        }
    }

    /** Data validation, not a rule: two conflicting records or a blank target leave agentRequested undefined. */
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