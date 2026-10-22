package com.nasim.camundaai.review.agent;

import com.nasim.camundaai.agent.AgentTaskCompletionPoller;
import com.nasim.camundaai.agent.AgentTaskExecutors;
import com.nasim.camundaai.agent.ResumeScoringExecutor;
import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.*;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskCompletionPollerTest {
    @Test void retriesUnauthorizedSearchOnlyOnce() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        server.createContext("/api/login", e -> {
            logins.incrementAndGet(); e.sendResponseHeaders(204, -1); e.close();
        });
        server.createContext("/v1/tasks/search", e -> {
            searches.incrementAndGet(); e.sendResponseHeaders(401, -1); e.close();
        });
        server.start();
        try {
            var poller = poller(server, mock(ResumeReviewRequestRepository.class),mock(ResumeReviewAttemptRepository.class),
                    mock(ClaudeScoringService.class));
            poller.completeAgentTasks();
            assertEquals(2, logins.get());
            assertEquals(2, searches.get());
        } finally { server.stop(0); }
    }

    @Test void reusesSavedDecisionAfterCompletionFailureAndSkipsStaleSearchResults() throws Exception {
        var requestRepository = mock(ResumeReviewRequestRepository.class);
        var attemptRepository = mock(ResumeReviewAttemptRepository.class);
        var scoring = mock(ClaudeScoringService.class);
        var job = mock(Job.class);
        var resume = mock(Resume.class);
        when(job.getDescription()).thenReturn("job");
        when(resume.getResumeText()).thenReturn("resume");
        var reviewRequest = new ResumeReviewRequest(job, resume, 123L);
        when(requestRepository.findByProcessInstanceKey(123L)).thenReturn(Optional.of(reviewRequest));
        var reviewAttempt= new ResumeReviewAttempt("456","Meets requirements","APPROVED",  82.0,
                "agent-bot",reviewRequest);
        when(attemptRepository.findByTaskIdAndReviewer("456","agent-bot")).thenReturn(Optional.empty()
        ,Optional.of(reviewAttempt));

        when(scoring.evaluateMatch("resume", "job"))
                .thenReturn(new ClaudeScoringService.ScoreResult(82, "APPROVED", "Meets requirements"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<String> searchBody = new AtomicReference<>();
        AtomicReference<String> completionBody = new AtomicReference<>();
        server.createContext("/api/login", e -> { e.sendResponseHeaders(204, -1); e.close(); });
        String task = "{\"id\":\"456\",\"processInstanceKey\":\"123\",\"taskDefinitionId\":\"Task_HrReview\",\"assignee\":\"agent-bot\",\"taskState\":\"CREATED\"}";
        server.createContext("/v1/tasks/search", e -> {
            searchBody.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("[" + task + "]").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/v1/tasks/456", e -> {
            byte[] body = task.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/v1/tasks/456/complete", e -> {
            completionBody.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            e.sendResponseHeaders(completions.incrementAndGet() == 1 ? 500 : 200, -1); e.close();
        });
        server.start();
        try {
            var poller = poller(server, requestRepository, attemptRepository,scoring);
            poller.completeAgentTasks();
            assertFalse(reviewAttempt.isAgentCompletionConfirmed());
            assertEquals("456", reviewAttempt.getTaskId());
            // A new poller represents a restart; the decision is held in the saved row.
            poller = poller(server, requestRepository,attemptRepository, scoring);
            poller.completeAgentTasks();
            poller.completeAgentTasks();
            verify(scoring, times(1)).evaluateMatch("resume", "job");
            assertEquals(2, completions.get());
            assertTrue(reviewAttempt.isAgentCompletionConfirmed());
            assertTrue(completionBody.get().contains("APPROVED"));
            assertTrue(completionBody.get().contains("reason"));
        } finally { server.stop(0); }
    }



    private AgentTaskCompletionPoller poller(HttpServer server, ResumeReviewRequestRepository requestRepository,
                                             ResumeReviewAttemptRepository reviewAttemptRepository,
                                             ClaudeScoringService scoring) {
        var tasklist = new TasklistClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "agent-bot", "test-password");
        return new AgentTaskCompletionPoller(tasklist,
                new AgentTaskExecutors(List.of(new ResumeScoringExecutor(scoring, requestRepository,reviewAttemptRepository))));
    }
}
