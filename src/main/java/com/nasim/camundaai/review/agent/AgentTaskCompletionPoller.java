package com.nasim.camundaai.review.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentTaskCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskCompletionPoller.class);
    private static final String AGENT_ASSIGNEE = "agent-bot";

    @Value("${tasklist.base-url:http://localhost:8082}")
    private String tasklistBaseUrl;

    @Value("${tasklist.username:agent-bot}")
    private String tasklistUsername;

    @Value("${tasklist.password:agent-bot-secret}")
    private String tasklistPassword;

    private final ResumeReviewRepository resumeReviewRepository;
    private final ClaudeScoringService claudeScoringService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // A CookieManager makes java.net.http.HttpClient keep the
    // TASKLIST-SESSION cookie across calls automatically - without it,
    // every request would look like a fresh, unauthenticated browser.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(new CookieManager())
            .build();

    private volatile boolean loggedIn = false;

    public AgentTaskCompletionPoller(ResumeReviewRepository resumeReviewRepository,
                                     ClaudeScoringService claudeScoringService) {
        this.resumeReviewRepository = resumeReviewRepository;
        this.claudeScoringService = claudeScoringService;
    }

    @Scheduled(fixedDelay = 10000)
    public void completeAgentTasks() {
        try {
            ensureLoggedIn();
            for (JsonNode task : searchAgentTasks()) {
                try {
                    completeOne(task);
                } catch (Exception e) {
                    log.error("Failed to complete task {} for agent-bot", task.path("id").asText(), e);
                }
            }
        } catch (Exception e) {
            log.error("agent-bot poller failed this round", e);
        }
    }
    private void ensureLoggedIn() throws Exception {
        if (!loggedIn) {
            login();
        }
    }

    private void login() throws Exception {
        String url = tasklistBaseUrl + "/api/login?username=" + URLEncoder.encode(tasklistUsername, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(tasklistPassword, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist login failed: " + response.statusCode() + " " + response.body());
        }
        loggedIn = true;
        log.info("agent-bot logged in to Tasklist as '{}'", tasklistUsername);
    }

    private List<JsonNode> searchAgentTasks() throws Exception {
        return searchAgentTasks(true);
    }

    private List<JsonNode> searchAgentTasks(boolean retryLogin) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "assignee", AGENT_ASSIGNEE,
                "state", "CREATED",
                "taskDefinitionId", "Task_HrReview",
                "pageSize", 100
        ));

        HttpRequest request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(tasklistBaseUrl + "/v1/tasks/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 && retryLogin) {
            // Session cookie expired - log in again and retry once.
            loggedIn = false;
            login();
            return searchAgentTasks(false);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist search failed: " + response.statusCode() + " " + response.body());
        }

        List<JsonNode> result = new ArrayList<>();
        objectMapper.readTree(response.body()).forEach(result::add);
        return result;
    }

    private void completeOne(JsonNode task) throws Exception {
        if (!"Task_HrReview".equals(task.path("taskDefinitionId").asText())) {
            return;
        }
        String taskId = task.path("id").asText();
        long processInstanceKey = Long.parseLong(task.path("processInstanceKey").asText());

        ResumeReview review = resumeReviewRepository.findByProcessInstanceKey(processInstanceKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReview found for process instance " + processInstanceKey));

        if (taskId.equals(review.getAgentTaskId()) && review.isAgentCompletionConfirmed()) {
            return; // Tasklist's search index may still show a completed task.
        }
        if (!taskId.equals(review.getAgentTaskId())) {
            ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
                    review.getResume().getResumeText(), review.getJob().getDescription());
            review.setMatchScore((double) result.score());
            review.setVerdict(result.decision());
            review.setReason(result.reason());
            review.setAgentTaskId(taskId);
            review.setAgentCompletionConfirmed(false);
            resumeReviewRepository.save(review);
        }

        // Recheck after scoring and before every completion attempt. A timeout
        // on the previous round may have happened after the engine completed it.
        JsonNode current = getTask(taskId, true);
        if ("COMPLETED".equals(current.path("taskState").asText())) {
            review.setAgentCompletionConfirmed(true);
            resumeReviewRepository.save(review);
            return;
        }
        if (!"CREATED".equals(current.path("taskState").asText())
                || !AGENT_ASSIGNEE.equals(current.path("assignee").asText())) {
            return;
        }
        completeTask(taskId, review.getMatchScore().intValue(), review.getVerdict(), review.getReason());
        review.setAgentCompletionConfirmed(true);
        resumeReviewRepository.save(review);
    }

    private JsonNode getTask(String taskId, boolean retryLogin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(tasklistBaseUrl + "/v1/tasks/" + taskId)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && retryLogin) {
            loggedIn = false;
            login();
            return getTask(taskId, false);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist status check failed: " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private void completeTask(String taskId, int matchScore, String verdict, String reason) throws Exception {
        // Tasklist expects each variable's "value" as a JSON-encoded string:
        // "82" for a number, "\"APPROVED\"" (quotes included) for a string.
        // Reusing Jackson to encode each value keeps this correct regardless
        // of type, instead of hand-building quotes and getting it wrong.
        List<Map<String, String>> variables = List.of(
                Map.of("name", "matchScore", "value", objectMapper.writeValueAsString(matchScore)),
                Map.of("name", "verdict", "value", objectMapper.writeValueAsString(verdict)),
                Map.of("name", "reason", "value", objectMapper.writeValueAsString(reason))
        );
        String body = objectMapper.writeValueAsString(Map.of("variables", variables));

        HttpResponse<String> response = sendComplete(taskId, body);

        if (response.statusCode() == 401) {
            loggedIn = false;
            login();
            response = sendComplete(taskId, body);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist complete failed: " + response.statusCode() + " " + response.body());
        }
        log.info("agent-bot completed task {} with matchScore={} verdict={}", taskId, matchScore, verdict);
    }

    private HttpResponse<String> sendComplete(String taskId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(tasklistBaseUrl + "/v1/tasks/" + taskId + "/complete"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
