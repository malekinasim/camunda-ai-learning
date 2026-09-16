package com.nasim.camundaai.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeScoringServiceTest {
    private final ClaudeScoringService service = new ClaudeScoringService();

    @Test void acceptsValidDecisionAndSeparateReason() throws Exception {
        var result = service.parseResult("```json\n{\"score\":82,\"decision\":\"APPROVED\",\"reason\":\"Meets requirements\"}\n```");
        assertEquals(82, result.score());
        assertEquals("APPROVED", result.decision());
        assertEquals("Meets requirements", result.reason());
    }

    @Test void rejectsMissingInvalidOrContradictoryValues() {
        for (String json : new String[]{
                "{}", "no JSON", "{\"score\":101,\"decision\":\"APPROVED\",\"reason\":\"x\"}",
                "{\"score\":\"82\",\"decision\":\"APPROVED\",\"reason\":\"x\"}",
                "{\"score\":82.5,\"decision\":\"APPROVED\",\"reason\":\"x\"}",
                "{\"score\":82,\"decision\":\"Good candidate\",\"reason\":\"x\"}",
                "{\"score\":82,\"decision\":\"REJECTED\",\"reason\":\"x\"}",
                "{\"score\":82,\"decision\":\"APPROVED\",\"reason\":\" \"}"}) {
            assertThrows(Exception.class, () -> service.parseResult(json), json);
        }
    }
}
