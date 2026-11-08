package com.nasim.camundaai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Reads skills/<name>/SKILL.md (and optional output.schema.json) from the classpath. */
@Component
public class SkillLoader {

    private static final String DELIMITER = "---";
    private final ObjectMapper mapper = new ObjectMapper();

    public Skill load(String name) {
        String folder = "skills/" + name + "/";
        String text = read(folder + "SKILL.md");

        // 1. Split "---\n<frontmatter>\n---\n<instructions>"
        String trimmed = text.strip();
        if (!trimmed.startsWith(DELIMITER)) {
            throw new IllegalStateException("Skill " + name + " has no frontmatter");
        }
        int end = trimmed.indexOf("\n" + DELIMITER, DELIMITER.length());
        if (end < 0) {
            throw new IllegalStateException("Skill " + name + ": frontmatter is not closed with ---");
        }
        String frontmatter = trimmed.substring(DELIMITER.length(), end);
        String instructions = trimmed.substring(end + 1 + DELIMITER.length()).strip();

        // 2. Read the frontmatter
        Object parsed = new Yaml().load(frontmatter);
        if (!(parsed instanceof Map<?, ?> meta)) {
            throw new IllegalStateException("Skill " + name + ": frontmatter is not a key/value list");
        }
        String declaredName = text(meta.get("name"));
        String version = text(meta.get("version"));
        String description = text(meta.get("description"));
        List<String> inputs = list(meta.get("inputs"), name);

        // 3. Fail closed: no usable skill means the agent does not run
        if (!name.equals(declaredName)) {
            throw new IllegalStateException("Skill folder " + name + " declares name " + declaredName);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Skill " + name + " has no version");
        }
        if (instructions.isBlank()) {
            throw new IllegalStateException("Skill " + name + " has no instructions");
        }

        // 4. Optional output contract: a main skill has one, a supporting skill does not
        JsonNode outputSchema = readSchema(folder + "output.schema.json", name);   // added
        return new Skill(name, version, description, instructions, inputs, outputSchema);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** "inputs: [a, b]" -> ["a", "b"]; missing -> empty list. */
    private static List<String> list(Object value, String skill) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalStateException("Skill " + skill + ": inputs must be a list, e.g. [resumeText, jobDescription]");
        }
        return items.stream().map(String::valueOf).toList();
    }

    /** Parsed here, so an invalid schema stops the application at startup, not at the first task. */
    private JsonNode readSchema(String path, String skill) {
        if (!new ClassPathResource(path).exists()) {
            return null;
        }
        try {
            return mapper.readTree(read(path));
        } catch (IOException e) {
            throw new IllegalStateException("Skill " + skill + ": output.schema.json is not valid JSON", e);
        }
    }

    private static String read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Skill file not found: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}