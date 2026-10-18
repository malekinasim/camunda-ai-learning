package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.tasklist.TasklistTask;

import java.util.Map;

/**
 * Does the work of an agent task and returns the variables to complete it with.
 * Must throw if it cannot produce a valid result: a failed attempt is retried,
 * a wrong result would be completed.
 */
public interface AgentTaskExecutor {
    Map<String, Object> execute(AgentTask task) throws Exception;

    void completeTask(TasklistTask task,String assignee);
}