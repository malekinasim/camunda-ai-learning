package com.nasim.camundaai.agent;

import com.nasim.camundaai.ai.ClaudeScoringService;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeScoringExecutorTest {

    private final ClaudeScoringService scoring = mock(ClaudeScoringService.class);
    private final ResumeReviewRequestRepository requestRepository = mock(ResumeReviewRequestRepository.class);
    private final ResumeReviewAttemptRepository attemptRepository = mock(ResumeReviewAttemptRepository.class);
    private final ResumeScoringExecutor executor = new ResumeScoringExecutor(scoring,
            new ResumeReviewRequestService(requestRepository),
            new ResumeReviewAttemptService(attemptRepository));

    // a tiny in-memory "table" of attempts, keyed by taskId/reviewer
    private final Map<String, ResumeReviewAttempt> attempts = new HashMap<>();

    @Test
    void secondAgentTaskInTheSameProcessIsScoredAgain() throws Exception {
        // Arrange: one review request for process 123
        Job job = mock(Job.class);
        Resume resume = mock(Resume.class);
        when(job.getDescription()).thenReturn("job");
        when(resume.getResumeText()).thenReturn("resume");
        ResumeReviewRequest request = new ResumeReviewRequest(job, resume, 123L, ResumeReviewRequest.RequestStatus.OPEN);
        when(requestRepository.findByProcessInstanceKey(123L)).thenReturn(Optional.of(request));

        // the attempt repository behaves like a table: save stores, find reads
        when(attemptRepository.save(any())).thenAnswer(inv -> {
            ResumeReviewAttempt a = inv.getArgument(0);
            attempts.put(a.getTaskId() + "/" + a.getReviewer(), a);
            return a;
        });
        when(attemptRepository.findByTaskIdAndReviewer(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(attempts.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        when(scoring.evaluateMatch("resume", "job"))
                .thenReturn(new ClaudeScoringService.ScoreResult(82, "APPROVED", "Meets requirements"));

        // the first agent task (456) is scored and completed
        executor.execute(new AgentTask("456", "CREATED", "agent-bot", 123L, "Task_HrReview"));
        executor.markAsAgentCompleteTask(new AgentTask("456", "COMPLETED", "agent-bot", 123L, "Task_HrReview"), "agent-bot");

        // Act: a second agent task (789) in the same process, e.g. after a loop-back
        AgentTaskResult second = executor.execute(new AgentTask("789", "CREATED", "agent-bot", 123L, "Task_HrReview"));

        // Assert
        assertFalse(second.alreadyCompleted());                         // the old bug returned true here
        verify(scoring, times(2)).evaluateMatch("resume", "job");      // scored again, not skipped
        assertEquals(AttemptStatus.COMPLETED, attempts.get("456/agent-bot").getStatus());
        assertEquals(AttemptStatus.RESULT_SAVED, attempts.get("789/agent-bot").getStatus());
    }

}