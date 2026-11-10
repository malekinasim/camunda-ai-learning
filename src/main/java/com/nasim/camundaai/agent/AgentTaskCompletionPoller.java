package com.nasim.camundaai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.agent.tasklist.TaskVariable;                              // added
import com.nasim.camundaai.gent.assignee.decisions.managment.DecisionUtils;          // added
import com.nasim.camundaai.gent.assignee.entity.AutonomyLevel;                       // added
import io.camunda.zeebe.client.ZeebeClient;                                          // added
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;                                                            // added
import java.util.List;                                                               // added
import java.util.Map;
import java.util.Optional;


@Component
public class AgentTaskCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskCompletionPoller.class);
    private static final String AGENT_ASSIGNEE = "agent-bot";
    /** Completion variable set only by the agent; the end listener trusts it more than Tasklist's index (#31). */
    public static final String COMPLETED_BY = "completedBy";
    private final TasklistClient tasklist;
    private final AgentTaskExecutors agentTaskExecutors;
    private final DecisionUtils decisionUtils;                                       // added
    private final ZeebeClient zeebeClient;                                           // added
    private final ObjectMapper mapper = new ObjectMapper();                          // added

    /** The final autonomy level and the person the task goes back to if needed. */
    record Decision(AutonomyLevel level, String defaultAssignee) { }                 // added

    public AgentTaskCompletionPoller(TasklistClient tasklist, AgentTaskExecutors agentTaskExecutors,
                                     DecisionUtils decisionUtils, ZeebeClient zeebeClient) {   // changed
        this.tasklist = tasklist;
        this.agentTaskExecutors = agentTaskExecutors;
        this.decisionUtils = decisionUtils;                                          // added
        this.zeebeClient = zeebeClient;                                              // added
    }

    @Scheduled(fixedDelay = 10000)
    public void completeAgentTasks() {
        try {
            for (TasklistTask task : tasklist.searchTasks(Map.of(
                    "assignee", AGENT_ASSIGNEE,
                    "state", TaskState.CREATED.name(),
                    "pageSize", 100
            ))) {
                try {
                    completeOne(task);
                } catch (Exception e) {
                    log.error("Failed to complete task {} ({}) for agent-bot", task.id(),task.implementation(), e);
                }
            }
        } catch (Exception e) {
            log.error("agent-bot poller failed this round", e);
        }
    }

    private void completeOne(TasklistTask task) throws Exception {
        Optional<AgentTaskExecutor> agentTaskExecutor=agentTaskExecutors.forType(task.taskDefinitionId());
        if (agentTaskExecutor.isEmpty()) {
            TasklistTask current = tasklist.getTask(task.id());                          // added: the search result may be stale
            if (!TaskState.CREATED.name().equals(current.taskState())
                    || !AGENT_ASSIGNEE.equals(current.assignee())) {
                log.info("Skipping unsupported task {}: state={}, assignee={}",
                        current.id(), current.taskState(), current.assignee());
                return;                                                                  // someone else owns it now: do not override
            }
            Object defaultAssignee = tasklist.searchTaskVariables(task.id(), List.of("defaultAssignee"))
                    .get("defaultAssignee");
            if (!(defaultAssignee instanceof String person) || person.isBlank()) {
                throw new IllegalStateException("No executor for " + task.taskDefinitionId()
                        + " and no defaultAssignee on task " + task.id());
            }
            handBack(task.id(), person);
            log.warn("No executor for task type {}; task {} handed back to {}",
                    task.taskDefinitionId(), task.id(), person);
            return;
        }
        AgentTaskResult result = agentTaskExecutor.get().execute(toAgentTask(task));

        boolean alreadyDone = result.alreadyCompleted();   // not a process variable
        if (alreadyDone) return;                       // we completed it earlier; search index may lag

        TasklistTask current = tasklist.getTask(task.id());
        if (TaskState.COMPLETED.name().equals(current.taskState())  ) {
            if(AGENT_ASSIGNEE.equals(current.assignee()) )
                // agent completed it earlier but missed the confirmed
                agentTaskExecutor.get().markAsAgentCompleteTask(toAgentTask(current), AGENT_ASSIGNEE);
            else {
                // a person completed it while the agent was working; the agent's result is not applied
                log.info("Task {} was completed by {}, not by {}; agent result not applied",
                        current.id(), current.assignee(), AGENT_ASSIGNEE);
                agentTaskExecutor.get().markAsWithdrawn(toAgentTask(current), AGENT_ASSIGNEE);
            }
            return;
        }
        if (!TaskState.CREATED.name().equals(current.taskState()) || !AGENT_ASSIGNEE.equals(current.assignee())) {
            //task state change before confirm agent decision or task state is canceled
            log.info("Skipping task {}: state={}, assignee={}",
                    current.id(), current.taskState(), current.assignee());
            agentTaskExecutor.get().markAsWithdrawn(toAgentTask(current), AGENT_ASSIGNEE);
            return;
        }

        Decision decision = applyAutonomy(task.id(), result.variables());              // added: after-checks
        switch (decision.level()) {                                                     // added
            case AGENT_AUTONOMOUS -> {
                Map<String, Object> completion = new HashMap<>(result.variables());
                completion.put(COMPLETED_BY, AGENT_ASSIGNEE);   // added (#31): tells the end listener the agent completed it
                tasklist.completeTask(task.id(), completion);
                agentTaskExecutor.get().markAsAgentCompleteTask(toAgentTask(tasklist.getTask(task.id())), AGENT_ASSIGNEE);
                log.info("Task {} completed by {}: verdict={}",                        // added (#18)
                        task.id(), AGENT_ASSIGNEE, result.variables().get("verdict"));
            }
            case AGENT_WITH_APPROVAL -> {
                zeebeClient.newSetVariablesCommand(task.processInstanceKey())        // the form reads these: the person sees the proposal
                        .variables(result.variables())
                        .send()
                        .join();
                handBack(task.id(), decision.defaultAssignee());
                log.info("Task {} handed back to {} for approval", task.id(), decision.defaultAssignee());
            }
            case HUMAN -> {
                handBack(task.id(), decision.defaultAssignee());                                                    // no proposal variables: independent decision (#46)
                agentTaskExecutor.get().markAsWithdrawn(toAgentTask(current), AGENT_ASSIGNEE);
                log.info("Task {} handed back to {}; agent proposal not shown", task.id(), decision.defaultAssignee());
            }
        }
    }
    /** Hands the same task back to a person. Through Zeebe: in Tasklist, agent-bot may only assign to itself. */
    private void handBack(String taskId, String assignee) {
        zeebeClient.newUserTaskAssignCommand(Long.parseLong(taskId))
                .assignee(assignee)
                .allowOverride(true)
                .send()
                .join();
    }
    /** Runs the after-checks and combines them with the before result. The strictest wins. */
    private Decision applyAutonomy(String taskId, Map<String, Object> proposal) throws Exception {   // added
        // 1. Task variables -> DMN input (a JSON object keyed by name)
        Map<String, Object> input =  tasklist.searchTaskVariables(taskId, List.of());
        input.put("proposal", proposal);                                             // the agent's output, kept separate

        // 2. Before (from the listener) and after (evaluated now)
        AutonomyLevel before = toLevel(input.get("autonomyBefore"));
        Object afterChecks = input.get("taskAfterDecisions");
        AutonomyLevel after = decisionUtils.makeFinalDecision(zeebeClient, mapper,
                afterChecks instanceof String s ? s : null,                          // missing list -> HUMAN
                mapper.writeValueAsString(input));

        // 3. The after-checks can never raise the before limit
        AutonomyLevel finalLevel = before.compareTo(after) <= 0 ? before : after;
        log.info("Task {}: autonomy before={}, after={}, final={}", taskId, before, after, finalLevel);

        // 4. Who gets the task back if a person is needed
        Object defaultAssignee = input.get("defaultAssignee");
        if (finalLevel != AutonomyLevel.AGENT_AUTONOMOUS
                && !(defaultAssignee instanceof String s && !s.isBlank())) {
            throw new IllegalStateException("Task " + taskId + " needs a person but has no defaultAssignee");
        }
        return new Decision(finalLevel, defaultAssignee instanceof String s ? s : null);
    }

    /** Missing or unknown value -> HUMAN (fail closed). */
    static AutonomyLevel toLevel(Object value) {                                     // added
        if (value instanceof String s) {
            try {
                return AutonomyLevel.valueOf(s);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown autonomy level '{}'; treated as HUMAN", s);
            }
        }
        return AutonomyLevel.HUMAN;
    }

    private AgentTask toAgentTask(TasklistTask task) {
        return  new AgentTask
                (task.id(), task.taskState(), task.assignee(), task.processInstanceKey(),
                        task.taskDefinitionId());
    }
}