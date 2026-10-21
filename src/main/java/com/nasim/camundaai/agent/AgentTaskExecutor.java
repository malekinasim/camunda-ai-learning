package com.nasim.camundaai.agent;

/**
 * Does the work of an agent task and returns the variables to complete it with.
 * Must throw if it cannot produce a valid result: a failed attempt is retried,
 * a wrong result would be completed.
 */
public interface AgentTaskExecutor {
    boolean supports(String taskType);
    AgentTaskResult execute(AgentTask task) throws Exception;

    void markAsAgentCompleteTask(AgentTask task, String assignee);
}