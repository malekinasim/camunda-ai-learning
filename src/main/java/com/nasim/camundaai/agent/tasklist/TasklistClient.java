package com.nasim.camundaai.agent.tasklist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

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

    public List<TasklistTask> searchTasks(Map<String,Object> searchMap) throws Exception {
        String body = objectMapper.writeValueAsString(searchMap);
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


    public Map<String,Object> searchTaskVariables(String taskId, List<String> variablesNames) throws Exception {
        HashMap<String,Object> map = new HashMap<>();
        if(!CollectionUtils.isEmpty(variablesNames)) {
            map.put(  "includeVariables",variablesNames.stream().map(
                    var->{
                        HashMap<String,Object> inner=new HashMap<>();
                        inner.put("name",var);
                        inner.put("alwaysReturnFullValue",true);
                        return inner;
                    }
            ).toList());
        }

        String body = objectMapper.writeValueAsString(map);
        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl +"/v1/tasks/" + taskId + "/variables/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Task variable search failed: " + response.statusCode() + " " + response.body());
        }
        HashMap<String,Object> result = new HashMap<>();
        for (JsonNode node : objectMapper.readTree(response.body())) {
            TaskVariable variable=toVariable(node);
            if (Boolean.TRUE.equals(variable.isValueTruncated())) {                 // added: a cut-off value is not the real value
                log.warn("Task {}: variable {} is truncated; skipped", taskId, variable.name());
                continue;
            }
            result.put(variable.name(),variable.value());
        }
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
        String body = objectMapper.writeValueAsString(Map.of("variables", encodeVariables(variables)));   // changed: helper

        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl + "/v1/tasks/" + taskId + "/complete"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist complete failed: " + response.statusCode() + " " + response.body());
        }
    }

    /** Saves variables as a draft: the form shows them, but the task is not completed. */
    public void saveDraftVariables(String taskId, Map<String, Object> variables) throws Exception {       // added
        String body = objectMapper.writeValueAsString(Map.of("variables", encodeVariables(variables)));

        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl + "/v1/tasks/" + taskId + "/variables"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist save draft failed: " + response.statusCode() + " " + response.body());
        }
    }

    /** Assigns the task to someone else; allowOverrideAssignment because it is currently agent-bot's. */
    public void assignTask(String taskId, String assignee) throws Exception {                            // added
        String body = objectMapper.writeValueAsString(Map.of(
                "assignee", assignee,
                "allowOverrideAssignment", true));

        HttpResponse<String> response = send(HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create(baseUrl + "/v1/tasks/" + taskId + "/assign"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tasklist assign failed: " + response.statusCode() + " " + response.body());
        }
    }

    /** Tasklist v1 wants each value as a JSON string: "REJECTED" -> "\"REJECTED\"". */
    private List<Map<String, String>> encodeVariables(Map<String, Object> variables) throws Exception {  // added: moved out of completeTask
        List<Map<String, String>> encoded = new ArrayList<>();
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            encoded.add(Map.of("name", variable.getKey(),
                    "value", objectMapper.writeValueAsString(variable.getValue())));
        }
        return encoded;
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
    private TaskVariable toVariable(JsonNode node) throws JsonProcessingException {      // fixed: not static, can throw
        JsonNode raw = node.get("value");
        Object value = (raw == null || raw.isNull())
                ? null
                : objectMapper.readValue(raw.asText(), Object.class);                    // fixed: "\"HUMAN\"" -> "HUMAN"
        return new TaskVariable(
                node.path("id").asText(),
                node.path("name").asText(),
                value,                                                                    // fixed: parsed value (Object)
                node.path("isValueTruncated").asBoolean(),                                // simpler than Boolean.valueOf(asText())
                node.path("previewValue").asText());
    }
}