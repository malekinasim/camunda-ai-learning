package com.nasim.camundaai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    private final SkillLoader loader = new SkillLoader();

    @Test
    void loadsTheResumeReviewSkill() {
        Skill skill = loader.load("resume-review");

        assertEquals("resume-review", skill.name());
        assertEquals("1", skill.version());
        assertTrue(skill.instructions().startsWith("You review resumes"));
        assertFalse(skill.instructions().contains("version:"));      // the frontmatter is not part of the instructions
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