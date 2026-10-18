package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.delegation.entity.TaskDelegation;
import com.nasim.camundaai.delegation.repository.TaskDelegationRepository;
import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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

       Map<String, Object> variableResult = agentTaskExecutor.get().execute(new AgentTask
                (task.id(), task.taskState(), task.assignee(), task.processInstanceKey(),
                        task.taskDefinitionId()));

        tasklist.completeTask(task.id(), variableResult);
        log.info("agent-bot completed task -first step{}", task.id());
        if (!(boolean) variableResult.get("isComplete")) {
            TasklistTask current = tasklist.getTask(task.id());
            agentTaskExecutor.get().completeTask(new AgentTask(current.id(), current.taskState(), current.assignee(), current.processInstanceKey(), current.taskDefinitionId()), AGENT_ASSIGNEE);

        }
    }
}