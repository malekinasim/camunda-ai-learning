package com.nasim.camundaai.review.agent;

import com.nasim.camundaai.agent.*;
import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class AgentTaskCompletionPollerRecheckTest {


    private final TasklistClient tasklist = mock(TasklistClient.class);
    private final AgentTaskExecutor executor = mock(AgentTaskExecutor.class);
    private final AgentTaskCompletionPoller poller =
            new AgentTaskCompletionPoller(tasklist, new AgentTaskExecutors(List.of(executor)));

    private static TasklistTask task(String assignee, String state) {
        return new TasklistTask("456", 123L, "Task_HrReview", assignee, state);
    }

    @Test
    void doesNotCompleteWhenTaskWasReassignedToAPerson() throws Exception {
        // 2. Arrange: what the fakes return
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(true);
        when(executor.execute(any())).thenReturn(new AgentTaskResult(Map.of("approved", true), false));
        when(tasklist.getTask("456")).thenReturn(task("anna", "CREATED"));   // a person took it over

        // 3. Act
        poller.completeAgentTasks();

        // 4. Assert: we really reached the re-check (the poller swallows exceptions,
        //    so without this a broken setup would also "pass")
        verify(tasklist).getTask("456");
        //    ... and the agent's result was not applied
        verify(tasklist, never()).completeTask(anyString(), anyMap());
        verify(executor, never()).markAsAgentCompleteTask(any(), anyString());
    }

    @Test
    void doesNotCompleteWhenTaskStateWasChanged() throws Exception {
        // 2. Arrange: what the fakes return
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(true);
        when(executor.execute(any())).thenReturn(new AgentTaskResult(Map.of("approved", true), false));
        when(tasklist.getTask("456")).thenReturn(task("agent-bot", "CANCELED"));   // the state changed

        // 3. Act
        poller.completeAgentTasks();

        // 4. Assert: we really reached the re-check (the poller swallows exceptions,
        //    so without this a broken setup would also "pass")
        verify(tasklist).getTask("456");
        //    ... and the agent's result was not applied
        verify(tasklist, never()).completeTask(anyString(), anyMap());
        verify(executor, never()).markAsAgentCompleteTask(any(), anyString());
    }
    @Test
    void doesNotCompleteWhenTaskStateWasCompletedBeforeByPerson() throws Exception {
        // 2. Arrange: what the fakes return
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(true);
        when(executor.execute(any())).thenReturn(new AgentTaskResult(Map.of("approved", true), false));
        when(tasklist.getTask("456")).thenReturn(task("anna", "COMPLETED"));   // a person had completed it

        // 3. Act
        poller.completeAgentTasks();

        // 4. Assert: we really reached the re-check (the poller swallows exceptions,
        //    so without this a broken setup would also "pass")
        verify(tasklist).getTask("456");
        //    ... and the agent's result was not applied
        verify(tasklist, never()).completeTask(anyString(), anyMap());
        verify(executor, never()).markAsAgentCompleteTask(any(), anyString());
    }
    @Test
    void doesNotCompleteWhenTaskStateWasCompletedBeforeByAgent() throws Exception {
        // 2. Arrange: what the fakes return
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(true);
        when(executor.execute(any())).thenReturn(new AgentTaskResult(Map.of("approved", true), false));
        when(tasklist.getTask("456")).thenReturn(task("agent-bot", "COMPLETED"));   // agent had completed it

        // 3. Act
        poller.completeAgentTasks();

        // 4. Assert: we really reached the re-check (the poller swallows exceptions,
        //    so without this a broken setup would also "pass")
        verify(tasklist).getTask("456");
        //    ... and the agent's result had applied before but will mark now
        verify(tasklist, never()).completeTask(anyString(), anyMap());
        verify(executor, times(1))
                .markAsAgentCompleteTask( eq(new AgentTask("456", "COMPLETED", "agent-bot", 123L, "Task_HrReview")),
                eq("agent-bot"));
    }


    @Test
    void completesWhenTaskIsStillTheAgents() throws Exception {
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(true);
        when(executor.execute(any())).thenReturn(new AgentTaskResult(Map.of("approved", true), false));
        when(tasklist.getTask("456")).thenReturn(task("agent-bot", "CREATED"),
                task("agent-bot", "COMPLETED"));
        poller.completeAgentTasks();

        verify(tasklist, times(1)).completeTask(eq("456"), eq(Map.of("approved", true)));
        verify(executor, times(1)).markAsAgentCompleteTask( eq(new AgentTask("456", "COMPLETED",
                        "agent-bot", 123L,
                        "Task_HrReview")),
                eq("agent-bot"));
    }
}