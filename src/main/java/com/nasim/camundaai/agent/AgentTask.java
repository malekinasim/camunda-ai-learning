package com.nasim.camundaai.agent;
/**
 * A task the agent has been asked to do, as the executor sees it.
 * Separate from TasklistTask: the executor should not care whether the
 * task came from Tasklist, a job worker or a test.
 */
public record AgentTask(String id, long processInstanceKey, String taskDefinitionId) {
}