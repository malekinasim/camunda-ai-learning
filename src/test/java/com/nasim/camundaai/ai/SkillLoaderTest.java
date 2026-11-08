package com.nasim.camundaai.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    private final SkillLoader loader = new SkillLoader();

    @Test
    void loadsTheResumeReviewSkill() {
        Skill skill = loader.load("resume-review");

        assertEquals("resume-review", skill.name());
        assertEquals("2", skill.version());
        assertTrue(skill.instructions().startsWith("You review resumes"));
        assertFalse(skill.instructions().contains("version:"));      // the frontmatter is not part of the instructions
    }
    @Test
    void readsInputsAndOutputSchemaOfAMainSkill() {
        Skill skill = loader.load("resume-review");

        assertEquals(List.of("resumeText", "jobDescription"), skill.inputs());
        assertNotNull(skill.outputSchema());
        assertEquals("resume-review@2", skill.versionId());
    }

    @Test
    void aSupportingSkillHasNoInputsAndNoSchema() {
        Skill skill = loader.load("hiring-policy");

        assertTrue(skill.inputs().isEmpty());
        assertNull(skill.outputSchema());
        assertFalse(skill.description().isBlank());
    }

    @Test
    void failsWhenTheSkillDoesNotExist() {
        assertThrows(IllegalStateException.class, () -> loader.load("does-not-exist"));
    }

    @Test
    void failsWhenTheSkillHasNoVersion() {
        assertThrows(IllegalStateException.class, () -> loader.load("test-no-version"));
    }

    @Test
    void failsWhenTheFolderAndTheDeclaredNameDiffer() {
        assertThrows(IllegalStateException.class, () -> loader.load("test-wrong-name"));
    }
}