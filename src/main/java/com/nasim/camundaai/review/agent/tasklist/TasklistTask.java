package com.nasim.camundaai.review.agent.tasklist;
/** Only the Tasklist fields the agent actually depends on. */
public record TasklistTask(String id,
                           long processInstanceKey,
                           String taskDefinitionId,
                           String assignee,
                           String taskState) {
}