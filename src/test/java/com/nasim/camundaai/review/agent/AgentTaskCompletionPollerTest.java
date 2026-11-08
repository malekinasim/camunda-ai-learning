package com.nasim.camundaai.review.agent;

import com.nasim.camundaai.agent.AgentTaskCompletionPoller;
import com.nasim.camundaai.agent.AgentTaskExecutors;
import com.nasim.camundaai.agent.ResumeScoringExecutor;
import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.ai.AgentService;
import com.nasim.camundaai.gent.assignee.decisions.managment.DecisionUtils;
import com.nasim.camundaai.gent.assignee.entity.AutonomyLevel;
import com.nasim.camundaai.review.entity.*;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import com.nasim.camundaai.review.repository.ResumeReviewRequestRepository;
import com.nasim.camundaai.review.service.ResumeReviewAttemptService;
import com.nasim.camundaai.review.service.ResumeReviewRequestService;
import com.sun.net.httpserver.HttpServer;
import io.camunda.zeebe.client.ZeebeClient;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
                    mock(AgentService.class));


            poller.completeAgentTasks();
            assertEquals(2, logins.get());
            assertEquals(2, searches.get());
        } finally { server.stop(0); }
    }

    @Test void reusesSavedDecisionAfterCompletionFailureAndSkipsStaleSearchResults() throws Exception {
        var requestRepository = mock(ResumeReviewRequestRepository.class);
        var attemptRepository = mock(ResumeReviewAttemptRepository.class);
        var scoring = mock(AgentService.class);
        var job = mock(Job.class);
        var resume = mock(Resume.class);
        when(job.getDescription()).thenReturn("job");
        when(resume.getResumeText()).thenReturn("resume");
        var reviewRequest = new ResumeReviewRequest(job, resume, 123L,ResumeReviewRequest.RequestStatus.OPEN);
        when(requestRepository.findByProcessInstanceKey(123L)).thenReturn(Optional.of(reviewRequest));
        AtomicReference<ResumeReviewAttempt> saved = new AtomicReference<>();
        when(attemptRepository.save(any())).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return inv.getArgument(0); });
        when(attemptRepository.findByTaskIdAndReviewer("456", "agent-bot"))
                .thenAnswer(inv -> Optional.ofNullable(saved.get()));

        // the task declares resume-review and no supporting skills (see the variables below)
        when(scoring.run(eq("resume-review"), eq(List.of()), anyMap()))
                .thenReturn(new AgentService.AgentResult(
                        Map.of("matchScore", 82, "verdict", "APPROVED", "reason", "Meets requirements"),
                        List.of("resume-review@2"), "test-model"));
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
        // Task variables as the listener left them; values are JSON strings, as Tasklist sends them
        String variables = "[{\"id\":\"1\",\"name\":\"autonomyBefore\",\"value\":\"\\\"AGENT_AUTONOMOUS\\\"\",\"isValueTruncated\":false},"
                + "{\"id\":\"2\",\"name\":\"defaultAssignee\",\"value\":\"\\\"hr-employee\\\"\",\"isValueTruncated\":false},"
                + "{\"id\":\"3\",\"name\":\"agentSkill\",\"value\":\"\\\"resume-review\\\"\",\"isValueTruncated\":false}]";
        server.createContext("/v1/tasks/456/variables/search", e -> {
            byte[] body = variables.getBytes(StandardCharsets.UTF_8);
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
            assertNotEquals(ResumeReviewAttempt.AttemptStatus.COMPLETED,saved.get().getStatus());
            assertEquals("456", saved.get().getTaskId());
            // A new poller represents a restart; the decision is held in the saved row.
            poller = poller(server, requestRepository,attemptRepository, scoring);
            poller.completeAgentTasks();
            poller.completeAgentTasks();
            verify(scoring, times(1)).run(anyString(), anyList(), anyMap());   // the model was called once
            assertEquals(2, completions.get());
            assertEquals(ResumeReviewAttempt.AttemptStatus.COMPLETED,saved.get().getStatus());
            assertEquals("resume-review@2", saved.get().getSkillVersion());
            assertTrue(completionBody.get().contains("APPROVED"));
            assertTrue(completionBody.get().contains("reason"));
            assertTrue(searchBody.get().contains("\"CREATED\""));   // the poller searches only active tasks
        } finally { server.stop(0); }
    }



    private AgentTaskCompletionPoller poller(HttpServer server, ResumeReviewRequestRepository requestRepository,
                                             ResumeReviewAttemptRepository reviewAttemptRepository,
                                             AgentService scoring) {
        var tasklist = new TasklistClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "agent-bot", "test-password");
        // These tests are about retries and saved results, not autonomy: the after-checks always allow it
        var decisionUtils = mock(DecisionUtils.class);
        when(decisionUtils.makeFinalDecision(any(), any(), any(), any())).thenReturn(AutonomyLevel.AGENT_AUTONOMOUS);
        return new AgentTaskCompletionPoller(tasklist,
                new AgentTaskExecutors(List.of(new ResumeScoringExecutor(scoring, tasklist,
                        new ResumeReviewRequestService(requestRepository),
                        new ResumeReviewAttemptService(reviewAttemptRepository)))),
                decisionUtils, mock(ZeebeClient.class));
    }
}
