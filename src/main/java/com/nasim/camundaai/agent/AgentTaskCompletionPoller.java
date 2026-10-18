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


@Component
public class AgentTaskCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskCompletionPoller.class);
    private static final String AGENT_ASSIGNEE = "agent-bot";
    private final TasklistClient tasklist;
    private final AgentTaskExecutor agentTaskExecutor;

    public AgentTaskCompletionPoller( TasklistClient tasklist,
                                      AgentTaskExecutor agentTaskExecutor) {

        this.tasklist = tasklist;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    @Scheduled(fixedDelay = 10000)
    public void completeAgentTasks() {
        try {
            for (TasklistTask task : tasklist.searchTasks(Map.of(
                    "assignee", AGENT_ASSIGNEE,
                    "state", "CREATE",
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
        Map<String, Object> variableResult= agentTaskExecutor.execute(new AgentTask(task.id(),task.processInstanceKey(),
                    task.taskDefinitionId()));


        tasklist.completeTask(task.id(),variableResult);
        log.info("agent-bot completed task -first step{}", task.id());

        agentTaskExecutor.completeTask( tasklist.getTask(task.id()),AGENT_ASSIGNEE);
    }
}