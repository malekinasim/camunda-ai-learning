package com.nasim.camundaai.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentServiceTest {

    private final SkillLoader loader = new SkillLoader();
    private final AgentService agent = new AgentService(loader, mock(ClaudeClient.class));
    private final Skill resumeReview = loader.load("resume-review");

    @Test
    void acceptsOutputThatMatchesTheSchema() throws Exception {
        Map<String, Object> out = agent.validate(resumeReview,
                "Here: {\"matchScore\": 82, \"verdict\": \"APPROVED\", \"reason\": \"Meets requirements\"}");
        assertEquals(82, out.get("matchScore"));
        assertEquals("APPROVED", out.get("verdict"));
    }

    @Test
    void rejectsScoreOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> agent.validate(resumeReview,
                "{\"matchScore\": 120, \"verdict\": \"APPROVED\", \"reason\": \"x\"}"));
    }

    @Test
    void rejectsUnknownVerdict() {
        assertThrows(IllegalArgumentException.class, () -> agent.validate(resumeReview,
                "{\"matchScore\": 80, \"verdict\": \"MAYBE\", \"reason\": \"x\"}"));
    }

    @Test
    void rejectsExtraFields() {
        assertThrows(IllegalArgumentException.class, () -> agent.validate(resumeReview,
                "{\"matchScore\": 80, \"verdict\": \"APPROVED\", \"reason\": \"x\", \"salary\": 1}"));
    }

    @Test
    void sendsOnlyTheDeclaredInputs() {
        String data = AgentService.userMessage(resumeReview,
                Map.of("resumeText", "R", "jobDescription", "J", "roleLevel", "SENIOR"));
        assertTrue(data.contains("<resumeText>"));
        assertFalse(data.contains("SENIOR"));                                   // not declared: not sent to the model
    }

    @Test
    void failsWhenADeclaredInputIsMissing() {
        assertThrows(IllegalStateException.class,
                () -> AgentService.userMessage(resumeReview, Map.of("resumeText", "R")));
    }

    @Test
    void supportingSkillsFollowTheMainInstructions() {
        String system = AgentService.systemPrompt(resumeReview, List.of(loader.load("skills/hiring-policy")));
        assertTrue(system.startsWith("You review resumes"));
        assertTrue(system.contains("## hiring-policy"));
    }
}