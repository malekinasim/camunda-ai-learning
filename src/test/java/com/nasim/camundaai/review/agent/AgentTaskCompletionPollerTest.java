package com.nasim.camundaai.review.agent;

import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.Job;
import com.nasim.camundaai.review.entity.Resume;
import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
            var poller = poller(server, mock(ResumeReviewRepository.class), mock(ClaudeScoringService.class));
            poller.completeAgentTasks();
            assertEquals(2, logins.get());
            assertEquals(2, searches.get());
        } finally { server.stop(0); }
    }

    @Test void reusesSavedDecisionAfterCompletionFailureAndSkipsStaleSearchResults() throws Exception {
        var repository = mock(ResumeReviewRepository.class);
        var scoring = mock(ClaudeScoringService.class);
        var job = mock(Job.class);
        var resume = mock(Resume.class);
        when(job.getDescription()).thenReturn("job");
        when(resume.getResumeText()).thenReturn("resume");
        var review = new ResumeReview(job, resume, 123L);
        when(repository.findByProcessInstanceKey(123L)).thenReturn(Optional.of(review));
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
            var poller = poller(server, repository, scoring);
            poller.completeAgentTasks();
            assertFalse(review.isAgentCompletionConfirmed());
            assertEquals("456", review.getAgentTaskId());
            // A new poller represents a restart; the decision is held in the saved row.
            poller = poller(server, repository, scoring);
            poller.completeAgentTasks();
            poller.completeAgentTasks();
            verify(scoring, times(1)).evaluateMatch("resume", "job");
            assertEquals(2, completions.get());
            assertTrue(review.isAgentCompletionConfirmed());
            assertTrue(searchBody.get().contains("Task_HrReview"));
            assertTrue(completionBody.get().contains("APPROVED"));
            assertTrue(completionBody.get().contains("reason"));
        } finally { server.stop(0); }
    }

    private AgentTaskCompletionPoller poller(HttpServer server, ResumeReviewRepository repository,
                                              ClaudeScoringService scoring) {
        var poller = new AgentTaskCompletionPoller(repository, scoring);
        ReflectionTestUtils.setField(poller, "tasklistBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(poller, "tasklistUsername", "agent-bot");
        ReflectionTestUtils.setField(poller, "tasklistPassword", "test-password");
        return poller;
    }
}
