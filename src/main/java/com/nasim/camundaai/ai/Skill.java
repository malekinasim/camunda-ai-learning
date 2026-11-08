package com.nasim.camundaai.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A loaded skill.
 * inputs: the task variables the model may see (empty for a supporting skill).
 * outputSchema: the JSON schema the result must match, or null for a supporting skill.
 */
public record Skill(String name, String version, String description, String instructions,
                    List<String> inputs, JsonNode outputSchema) {

    /** "resume-review@2": stored on every result. */
    public String versionId() {
        return name + "@" + version;
    }
}