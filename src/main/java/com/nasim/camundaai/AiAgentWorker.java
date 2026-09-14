package com.nasim.camundaai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is the bridge between Zeebe (the BPMN engine) and the Claude API.
 * <p>
 * How this gets called, step by step:
 * 1. A process instance reaches the "Evaluate resume match with AI" service
 * task in resume-review.bpmn (task type "call-ai-agent").
 * 2. Zeebe creates a job and offers it to any worker subscribed to that type.
 * 3. Because of @JobWorker(type = "call-ai-agent") below, Spring routes that
 * job straight into the evaluateMatch() method.
 * 4. @Variable pulls the named process variables out of the job for us,
 * as plain method parameters - no manual JSON parsing needed.
 * 5. Whatever Map we return here becomes the new set of process variables,
 * which is what lets the BPMN gateway read "matchScore" afterwards.
 */
@Component
public class AiAgentWorker {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model}")
    private String model;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @JobWorker(type = "call-ai-agent")
    public Map<String, Object> evaluateMatch(
            @Variable String resumeText,
            @Variable String jobDescription) throws Exception {

        String prompt = """
                Resume:
                %s

                Job description:
                %s

                Score how well this resume matches this job from 0 to 100.
                Respond with ONLY a JSON object, no other text, in this exact shape:
                {"score": <integer 0-100>, "verdict": "<one sentence, no sugar-coating>"}
                """.formatted(resumeText, jobDescription);

        String responseText = callClaude(prompt);
        JsonNode result = mapper.readTree(extractJson(responseText));

        Map<String, Object> outputVariables = new HashMap<>();
        outputVariables.put("matchScore", result.path("score").asInt());
        outputVariables.put("verdict", result.path("verdict").asText());
        return outputVariables;
        // Returning this Map is enough - because autoComplete defaults to
        // true, the Spring integration completes the job for us and writes
        // these entries into the process's variables automatically.
    }

    private String callClaude(String prompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 500);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        body.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Claude API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        return root.path("content").get(0).path("text").asText();
    }

    /**
     * Claude sometimes wraps JSON in a sentence or code fences even when asked
     * not to. This pulls out just the {...} part so parsing doesn't break.
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return text.substring(start, end + 1);
    }
}
