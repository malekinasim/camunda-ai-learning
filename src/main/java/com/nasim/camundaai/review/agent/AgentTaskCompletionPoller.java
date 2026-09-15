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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * agent-bot's "hands": since agent-bot has no UI, this poller plays the role
 * a human plays when they open Tasklist and submit the HR review form -
 * except it talks to the Tasklist REST API directly instead of a browser.
 * <p>
 * This replaces an earlier version built on zeebeClient.newUserTaskQuery(),
 * which is @ExperimentalApi and threw MalformedResponseException in practice
 * against this broker. The Tasklist v1 REST API (POST /v1/tasks/search,
 * PATCH /v1/tasks/{id}/complete) is stable and handles both discovery and
 * completion, so ZeebeClient is no longer a dependency of this class at all.
 * <p>
 * Auth: Tasklist self-managed defaults to a built-in demo/demo user backed by
 * Elasticsearch - POST /api/login sets a session cookie, which the
 * CookieManager below remembers automatically for every later request, the
 * same way Postman's cookie jar did while this flow was being verified by
 * hand. CSRF prevention needs to be off on the tasklist service
 * (CAMUNDA_TASKLIST_CSRFPREVENTIONENABLED=false in docker-compose.yml) or
 * every POST/PATCH here gets a 403.
 */
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
        String url = tasklistBaseUrl + "/api/login?username=" + tasklistUsername + "&password=" + tasklistPassword;
        HttpRequest request = HttpRequest.newBuilder()
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
        String body = objectMapper.writeValueAsString(Map.of(
                "assignee", AGENT_ASSIGNEE,
                "state", "CREATED"
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tasklistBaseUrl + "/v1/tasks/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            // Session cookie expired - log in again and retry once.
            loggedIn = false;
            login();
            return searchAgentTasks();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist search failed: " + response.statusCode() + " " + response.body());
        }

        List<JsonNode> result = new ArrayList<>();
        objectMapper.readTree(response.body()).forEach(result::add);
        return result;
    }

    private void completeOne(JsonNode task) throws Exception {
        String taskId = task.path("id").asText();
        long processInstanceKey = Long.parseLong(task.path("processInstanceKey").asText());

        ResumeReview review = resumeReviewRepository.findByProcessInstanceKey(processInstanceKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReview found for process instance " + processInstanceKey));

        ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
                review.getResume().getResumeText(),
                review.getJob().getDescription());

        review.setMatchScore((double) result.score());
        review.setVerdict(result.verdict());
        resumeReviewRepository.save(review);

        completeTask(taskId, result.score(), result.verdict());
    }

    private void completeTask(String taskId, int matchScore, String verdict) throws Exception {
        // Tasklist expects each variable's "value" as a JSON-encoded string:
        // "82" for a number, "\"APPROVED\"" (quotes included) for a string.
        // Reusing Jackson to encode each value keeps this correct regardless
        // of type, instead of hand-building quotes and getting it wrong.
        List<Map<String, String>> variables = List.of(
                Map.of("name", "matchScore", "value", objectMapper.writeValueAsString(matchScore)),
                Map.of("name", "verdict", "value", objectMapper.writeValueAsString(verdict))
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tasklistBaseUrl + "/v1/tasks/" + taskId + "/complete"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}