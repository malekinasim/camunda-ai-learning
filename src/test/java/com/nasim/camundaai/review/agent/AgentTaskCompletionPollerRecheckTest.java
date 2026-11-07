package com.nasim.camundaai.review.agent;

import com.nasim.camundaai.agent.*;
import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.gent.assignee.decisions.managment.DecisionUtils;
import com.nasim.camundaai.gent.assignee.entity.AutonomyLevel;
import io.camunda.zeebe.client.ZeebeClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class AgentTaskCompletionPollerRecheckTest {


    private final TasklistClient tasklist = mock(TasklistClient.class);
    private final AgentTaskExecutor executor = mock(AgentTaskExecutor.class);
    private final DecisionUtils decisionUtils = mock(DecisionUtils.class);
    // Deep stubs: every step of zeebeClient.newX(...).a(...).send().join() returns a mock,
    // and the same arguments return the same step mock, so we can verify the chain.
    private final ZeebeClient zeebeClient = mock(ZeebeClient.class, RETURNS_DEEP_STUBS);
    private final AgentTaskCompletionPoller poller =
            new AgentTaskCompletionPoller(tasklist, new AgentTaskExecutors(List.of(executor)),
                    decisionUtils, zeebeClient);

    /** The task (key 456) was handed back to hr-employee through Zeebe. */
    private void verifyHandedBackToEmployee() {
        verify(zeebeClient.newUserTaskAssignCommand(456L)).assignee("hr-employee");
    }

    private static TasklistTask task(String assignee, String state) {
        return new TasklistTask("456", 123L, "Task_HrReview", assignee, state,"ZEEBE_USER_TASK");
    }

    /** Task variables as the listener left them. A HashMap: the poller adds "proposal" to it. */
    private static Map<String, Object> taskVariables(String autonomyBefore) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("autonomyBefore", autonomyBefore);
        variables.put("taskAfterDecisions", "hr-outcome");
        variables.put("defaultAssignee", "hr-employee");
        return variables;
    }

    /** The agent produced a proposal and the task is still the agent's. */
    private void agentHasAProposal(String autonomyBefore, AutonomyLevel after) throws Exception {
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(true);
        when(executor.execute(any())).thenReturn(new AgentTaskResult(Map.of("approved", true), false));
        when(tasklist.getTask("456")).thenReturn(task("agent-bot", "CREATED"),
                task("agent-bot", "COMPLETED"));
        when(tasklist.searchTaskVariables(eq("456"), anyList())).thenReturn(taskVariables(autonomyBefore));
        when(decisionUtils.makeFinalDecision(any(), any(), any(), any())).thenReturn(after);
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
    void completesWhenTaskIsStillTheAgentsAndAutonomous() throws Exception {
        agentHasAProposal("AGENT_AUTONOMOUS", AutonomyLevel.AGENT_AUTONOMOUS);

        poller.completeAgentTasks();

        verify(tasklist, times(1)).completeTask(eq("456"), eq(Map.of("approved", true)));
        verify(executor, times(1)).markAsAgentCompleteTask( eq(new AgentTask("456", "COMPLETED",
                        "agent-bot", 123L,
                        "Task_HrReview")),
                eq("agent-bot"));
        verify(zeebeClient, never()).newUserTaskAssignCommand(anyLong());
        verify(zeebeClient, never()).newSetVariablesCommand(anyLong());
    }

    @Test
    void handsBackWithDraftWhenAfterCheckNeedsApproval() throws Exception {
        agentHasAProposal("AGENT_AUTONOMOUS", AutonomyLevel.AGENT_WITH_APPROVAL);   // e.g. hr-outcome: a reject

        poller.completeAgentTasks();

        var setVariables = zeebeClient.newSetVariablesCommand(123L);               // process instance 123
        var assign = zeebeClient.newUserTaskAssignCommand(456L);                   // user task 456
        InOrder order = inOrder(setVariables, assign);                              // the proposal must be there before the person gets the task
        order.verify(setVariables).variables(eq(Map.of("approved", true)));
        order.verify(assign).assignee("hr-employee");
        verify(tasklist, never()).completeTask(anyString(), anyMap());
        verify(executor, never()).markAsAgentCompleteTask(any(), anyString());
    }

    @Test
    void beforeLimitIsKeptWhenAfterCheckAllowsMore() throws Exception {
        agentHasAProposal("AGENT_WITH_APPROVAL", AutonomyLevel.AGENT_AUTONOMOUS);   // e.g. senior role, approved resume

        poller.completeAgentTasks();

        verify(zeebeClient.newSetVariablesCommand(123L)).variables(anyMap());
        verifyHandedBackToEmployee();
        verify(tasklist, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void handsBackWithoutDraftWhenHuman() throws Exception {
        agentHasAProposal("AGENT_AUTONOMOUS", AutonomyLevel.HUMAN);

        poller.completeAgentTasks();

        verifyHandedBackToEmployee();
        verify(zeebeClient, never()).newSetVariablesCommand(anyLong());             // the person decides independently (#46)
        verify(tasklist, never()).completeTask(anyString(), anyMap());
        verify(executor).markAsWithdrawn(any(), eq("agent-bot"));
    }

    @Test
    void missingOrUnknownBeforeCountsAsHuman() throws Exception {
        agentHasAProposal("NOT_A_LEVEL", AutonomyLevel.AGENT_AUTONOMOUS);

        poller.completeAgentTasks();

        verifyHandedBackToEmployee();
        verify(zeebeClient, never()).newSetVariablesCommand(anyLong());
        verify(tasklist, never()).completeTask(anyString(), anyMap());
    }
    @Test
    void handsBackToDefaultAssigneeWhenNoExecutorSupportsTheTask() throws Exception {
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(false);                  // no executor for this task type
        when(tasklist.searchTaskVariables(eq("456"), anyList()))
                .thenReturn(new HashMap<>(Map.of("defaultAssignee", "hr-employee")));

        poller.completeAgentTasks();

        verifyHandedBackToEmployee();                                               // back to the person, through Zeebe
        verify(executor, never()).execute(any());                                   // the agent did no work
        verify(tasklist, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void doesNotHandBackWhenThereIsNoDefaultAssignee() throws Exception {
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(task("agent-bot", "CREATED")));
        when(executor.supports("Task_HrReview")).thenReturn(false);
        when(tasklist.searchTaskVariables(eq("456"), anyList())).thenReturn(new HashMap<>());   // defaultAssignee missing

        poller.completeAgentTasks();                                                // logs the error, does not throw

        verify(tasklist).searchTaskVariables(eq("456"), anyList());                 // we really reached the check
        verify(zeebeClient, never()).newUserTaskAssignCommand(anyLong());           // nobody to give it to: no assign
        verify(executor, never()).execute(any());
    }
}
