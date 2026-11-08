package com.nasim.camundaai.review.agent;

import com.nasim.camundaai.agent.AgentTask;
import com.nasim.camundaai.agent.AgentTaskResult;
import com.nasim.camundaai.agent.ResumeScoringExecutor;
import com.nasim.camundaai.agent.tasklist.TasklistClient;                       // added
import com.nasim.camundaai.ai.AgentService;                                      // added
import com.nasim.camundaai.review.entity.Job;
import com.nasim.camundaai.review.entity.Resume;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt.AttemptStatus;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import com.nasim.camundaai.review.service.ResumeReviewAttemptService;
import com.nasim.camundaai.review.service.ResumeReviewRequestService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;                                                           // added
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeScoringExecutorTest {

    private final AgentService agent = mock(AgentService.class);                 // changed: was ClaudeScoringService
    private final TasklistClient tasklist = mock(TasklistClient.class);          // added: the task's variables
    private final ResumeReviewRequestRepository requestRepository = mock(ResumeReviewRequestRepository.class);
    private final ResumeReviewAttemptRepository attemptRepository = mock(ResumeReviewAttemptRepository.class);
    private final ResumeScoringExecutor executor = new ResumeScoringExecutor(agent, tasklist,   // changed
            new ResumeReviewRequestService(requestRepository),
            new ResumeReviewAttemptService(attemptRepository));

    // a tiny in-memory "table" of attempts, keyed by taskId/reviewer
    private final Map<String, ResumeReviewAttempt> attempts = new HashMap<>();

    @Test
    void secondAgentTaskInTheSameProcessIsScoredAgain() throws Exception {
        // Arrange: one review request for process 123
        ResumeReviewRequest request = new ResumeReviewRequest(mock(Job.class), mock(Resume.class), 123L,
                ResumeReviewRequest.RequestStatus.OPEN);
        when(requestRepository.findByProcessInstanceKey(123L)).thenReturn(Optional.of(request));

        // the attempt repository behaves like a table: save stores, find reads
        when(attemptRepository.save(any())).thenAnswer(inv -> {
            ResumeReviewAttempt a = inv.getArgument(0);
            attempts.put(a.getTaskId() + "/" + a.getReviewer(), a);
            return a;
        });
        when(attemptRepository.findByTaskIdAndReviewer(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(attempts.get(inv.getArgument(0) + "/" + inv.getArgument(1))));

        // the task declares its skills, as the BPMN inputs do                    // changed
        when(tasklist.searchTaskVariables(anyString(), anyList())).thenReturn(Map.of(
                "agentSkill", "resume-review", "agentSkillsExtra", "hiring-policy",
                "resumeText", "resume", "jobDescription", "job"));
        when(agent.run(eq("resume-review"), eq(List.of("hiring-policy")), anyMap()))
                .thenReturn(new AgentService.AgentResult(
                        Map.of("matchScore", 82, "verdict", "APPROVED", "reason", "Meets requirements"),
                        List.of("resume-review@2", "hiring-policy@1"), "test-model"));

        // the first agent task (456) is scored and completed
        executor.execute(new AgentTask("456", "CREATED", "agent-bot", 123L, "Task_HrReview"));
        executor.markAsAgentCompleteTask(new AgentTask("456", "COMPLETED", "agent-bot", 123L, "Task_HrReview"), "agent-bot");

        // Act: a second agent task (789) in the same process, e.g. after a loop-back
        AgentTaskResult second = executor.execute(new AgentTask("789", "CREATED", "agent-bot", 123L, "Task_HrReview"));

        // Assert
        assertFalse(second.alreadyCompleted());                                  // the old bug returned true here
        verify(agent, times(2)).run(anyString(), anyList(), anyMap());           // changed: run again, not skipped
        assertEquals("resume-review@2,hiring-policy@1",                          // added: both skill versions stored
                attempts.get("789/agent-bot").getSkillVersion());
        assertEquals(AttemptStatus.COMPLETED, attempts.get("456/agent-bot").getStatus());
        assertEquals(AttemptStatus.RESULT_SAVED, attempts.get("789/agent-bot").getStatus());
    }
}