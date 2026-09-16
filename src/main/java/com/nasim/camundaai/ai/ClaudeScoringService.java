package com.nasim.camundaai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ClaudeScoringService {

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

    public record ScoreResult(int score, String decision, String reason) {
    }

    public ScoreResult evaluateMatch(String resumeText, String jobDescription) throws Exception {
        String prompt = """
                Resume:
                %s

                Job description:
                %s

                Score how well this resume matches this job from 0 to 100.
                Respond with ONLY a JSON object, no other text, in this exact shape:
                {"score": <integer 0-100>, "decision": "APPROVED or REJECTED", "reason": "<one sentence>"}
                For this demo, APPROVED means score >= 50; otherwise use REJECTED.
                """.formatted(resumeText, jobDescription);

        String responseText = callClaude(prompt);
        return parseResult(responseText);
    }

    ScoreResult parseResult(String text) throws Exception {
        JsonNode result = mapper.readTree(extractJson(text));
        JsonNode score = result.path("score");
        JsonNode decision = result.path("decision");
        JsonNode reason = result.path("reason");
        if (!score.isIntegralNumber() || !score.canConvertToInt()
                || score.intValue() < 0 || score.intValue() > 100
                || !decision.isTextual()
                || !("APPROVED".equals(decision.textValue()) || "REJECTED".equals(decision.textValue()))
                || !reason.isTextual() || reason.textValue().isBlank()) {
            throw new IllegalArgumentException("Invalid scoring result: expected score 0-100, decision and reason");
        }
        String expected = score.intValue() >= 50 ? "APPROVED" : "REJECTED";
        if (!expected.equals(decision.textValue())) {
            throw new IllegalArgumentException("Decision contradicts the demo threshold");
        }
        return new ScoreResult(score.intValue(), decision.textValue(), reason.textValue());
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

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Model response does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}
