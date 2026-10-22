package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;


@Component
public class AgentTaskCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskCompletionPoller.class);
    private static final String AGENT_ASSIGNEE = "agent-bot";
    private final TasklistClient tasklist;
    private final AgentTaskExecutors agentTaskExecutors;
    public AgentTaskCompletionPoller(TasklistClient tasklist, AgentTaskExecutors agentTaskExecutors) {

        this.tasklist = tasklist;
        this.agentTaskExecutors = agentTaskExecutors;
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
                    log.error("Failed to complete task {} for agent-bot", task.id(), e);
                }
            }
        } catch (Exception e) {
            log.error("agent-bot poller failed this round", e);
        }
    }

    private void completeOne(TasklistTask task) throws Exception {
        Optional<AgentTaskExecutor> agentTaskExecutor=agentTaskExecutors.forType(task.taskDefinitionId());

        if(agentTaskExecutor.isEmpty())//TODO we should redelegated it to defaultAssignee
            throw new IllegalArgumentException("this task definition " + task.taskDefinitionId() + "is not for this executor");


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
        tasklist.completeTask(task.id(), result.variables());
        agentTaskExecutor.get().markAsAgentCompleteTask(toAgentTask(tasklist.getTask(task.id())), AGENT_ASSIGNEE);

    }

    private AgentTask toAgentTask(TasklistTask task) {
         return  new AgentTask
                (task.id(), task.taskState(), task.assignee(), task.processInstanceKey(),
                        task.taskDefinitionId());
    }
}