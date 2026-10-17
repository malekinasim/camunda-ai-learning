package com.nasim.camundaai.review.agent.tasklist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TasklistClient {

    private static final Logger log = LoggerFactory.getLogger(TasklistClient.class);

    private final String baseUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // A CookieManager makes java.net.http.HttpClient keep the
    // TASKLIST-SESSION cookie across calls automatically - without it,
    // every request would look like a fresh, unauthenticated browser.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(new CookieManager())
            .build();

    private volatile boolean loggedIn = false;

    public TasklistClient(@Value("${tasklist.base-url:http://localhost:8082}") String baseUrl,
                          @Value("${tasklist.username:agent-bot}") String username,
                          @Value("${tasklist.password:agent-bot-secret}") String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    public String username() {
        return username;
    }

    public List<TasklistTask> searchTasks(String assignee, String state, String taskDefinitionId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "assignee", assignee,
                "state", state,
                "taskDefinitionId", taskDefinitionId,
                "pageSize", 100
        ));
        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl + "/v1/tasks/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist search failed: " + response.statusCode() + " " + response.body());
        }
        List<TasklistTask> result = new ArrayList<>();
        objectMapper.readTree(response.body()).forEach(node -> result.add(toTask(node)));
        return result;
    }

    public TasklistTask getTask(String taskId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl + "/v1/tasks/" + taskId)).GET().build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist status check failed: " + response.statusCode());
        }
        return toTask(objectMapper.readTree(response.body()));
    }

    public void completeTask(String taskId, Map<String, Object> variables) throws Exception {
        // Tasklist expects each variable's "value" as a JSON-encoded string:
        // "82" for a number, "\"APPROVED\"" (quotes included) for a string.
        // Reusing Jackson to encode each value keeps this correct regardless
        // of type, instead of hand-building quotes and getting it wrong.
        List<Map<String, String>> encoded = new ArrayList<>();
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            encoded.add(Map.of("name", variable.getKey(),
                    "value", objectMapper.writeValueAsString(variable.getValue())));
        }
        String body = objectMapper.writeValueAsString(Map.of("variables", encoded));

        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl + "/v1/tasks/" + taskId + "/complete"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist complete failed: " + response.statusCode() + " " + response.body());
        }
    }

    /**
     * Sends a request, logging in first if needed. If the session cookie has
     * expired (401), logs in again and retries exactly once - never in a loop.
     */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        if (!loggedIn) {
            login();
        }
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            loggedIn = false;
            login();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private void login() throws Exception {
        String url = baseUrl + "/api/login?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist login failed: " + response.statusCode() + " " + response.body());
        }
        loggedIn = true;
        log.info("Logged in to Tasklist as '{}'", username);
    }

    private static TasklistTask toTask(JsonNode node) {
        return new TasklistTask(
                node.path("id").asText(),
                Long.parseLong(node.path("processInstanceKey").asText()),
                node.path("taskDefinitionId").asText(),
                node.path("assignee").asText(),
                node.path("taskState").asText());
    }
}