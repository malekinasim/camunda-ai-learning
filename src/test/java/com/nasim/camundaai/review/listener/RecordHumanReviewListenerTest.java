package com.nasim.camundaai.review.listener;

import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt.AttemptStatus;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt.ProposalReview;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.service.ResumeReviewAttemptService;
import com.nasim.camundaai.review.service.ResumeReviewRequestService;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecordHumanReviewListenerTest {

    private static final long PROCESS = 42L;
    private static final String TASK = "100";

    private final TasklistClient tasklist = mock(TasklistClient.class);
    private final ResumeReviewRequestService requests = mock(ResumeReviewRequestService.class);
    private final ResumeReviewAttemptService attempts = mock(ResumeReviewAttemptService.class);
    private final RecordHumanReviewListener listener = new RecordHumanReviewListener(tasklist, requests, attempts);
    private final ResumeReviewRequest request = new ResumeReviewRequest();

    @BeforeEach
    void aReviewRequestExists() {
        when(requests.findByProcessInstanceKey(PROCESS)).thenReturn(Optional.of(request));
    }

    @Test
    void recordsAnAcceptedProposal() throws Exception {
        completedBy("hr-employee");
        when(attempts.find(TASK, "agent-bot")).thenReturn(Optional.of(proposal("REJECTED", 30, AttemptStatus.RESULT_SAVED)));

        listener.recordHumanReview(job("REJECTED", 30));

        verify(attempts).saveHumanDecision(TASK, "hr-employee", request, 30.0, "REJECTED", "reason",
                ProposalReview.ACCEPTED);
        verify(requests).closeWithLatestCompletedAttempt(PROCESS);              // the person's decision is final
    }

    @Test
    void recordsAChangedVerdict() throws Exception {
        completedBy("hr-employee");
        when(attempts.find(TASK, "agent-bot")).thenReturn(Optional.of(proposal("REJECTED", 30, AttemptStatus.RESULT_SAVED)));

        listener.recordHumanReview(job("APPROVED", 70));

        verify(attempts).saveHumanDecision(eq(TASK), eq("hr-employee"), eq(request), eq(70.0), eq("APPROVED"),
                any(), eq(ProposalReview.VERDICT_CHANGED));
    }

    @Test
    void skipsTheAgentsOwnCompletion() throws Exception {
        completedBy("agent-bot");

        listener.recordHumanReview(job("APPROVED", 95));

        verify(attempts, never()).saveHumanDecision(any(), any(), any(), anyDouble(), any(), any(), any());
    }

    @Test
    void aRetryDoesNotRecordTwiceButStillClosesTheRequest() throws Exception {
        completedBy("hr-employee");
        when(attempts.find(TASK, "hr-employee")).thenReturn(Optional.of(proposal("REJECTED", 30, AttemptStatus.COMPLETED)));

        listener.recordHumanReview(job("REJECTED", 30));

        verify(attempts, never()).saveHumanDecision(any(), any(), any(), anyDouble(), any(), any(), any());
        verify(requests).closeWithLatestCompletedAttempt(PROCESS);              // the save may have succeeded and the close failed
    }

    @Test
    void comparesOnlyWithAProposalThePersonSaw() {
        assertEquals(ProposalReview.NO_PROPOSAL,
                RecordHumanReviewListener.compare(Optional.empty(), "APPROVED", 80));
        assertEquals(ProposalReview.NO_PROPOSAL,                               // HUMAN path: withdrawn, not shown (#46)
                RecordHumanReviewListener.compare(Optional.of(proposal("APPROVED", 80, AttemptStatus.WITHDRAWN)), "APPROVED", 80));
        assertEquals(ProposalReview.SCORE_CHANGED,
                RecordHumanReviewListener.compare(Optional.of(proposal("REJECTED", 30, AttemptStatus.RESULT_SAVED)), "REJECTED", 20));
    }

    // helpers

    private void completedBy(String assignee) throws Exception {
        when(tasklist.searchTasks(anyMap())).thenReturn(List.of(
                new TasklistTask(TASK, PROCESS, "Task_HrReview", assignee, "COMPLETED", "ZEEBE_USER_TASK")));
    }

    private static ResumeReviewAttempt proposal(String verdict, double score, AttemptStatus status) {
        return new ResumeReviewAttempt(TASK, "agent reason", verdict, score, "agent-bot", null, status);
    }

    private static ActivatedJob job(String verdict, int score) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getProcessInstanceKey()).thenReturn(PROCESS);
        when(job.getElementId()).thenReturn("Task_HrReview");
        Map<String, Object> variables = new HashMap<>();
        variables.put("verdict", verdict);
        variables.put("matchScore", score);
        variables.put("reason", "reason");
        when(job.getVariablesAsMap()).thenReturn(variables);
        return job;
    }
}
