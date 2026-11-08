package com.nasim.camundaai.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Reads skills/<name>/SKILL.md from the classpath. A skill without a version is not usable. */
@Component
public class SkillLoader {

    private static final String DELIMITER = "---";

    public Skill load(String name) {
        String text = read("skills/" + name + "/SKILL.md");

        String trimmed = text.strip();
        if (!trimmed.startsWith(DELIMITER)) {
            throw new IllegalStateException("Skill " + name + " has no frontmatter");
        }
        int end = trimmed.indexOf("\n" + DELIMITER, DELIMITER.length());
        if (end < 0) {
            throw new IllegalStateException("Skill " + name + ": frontmatter is not closed with ---");
        }
        String metadata = trimmed.substring(DELIMITER.length(), end);
        String instructions = trimmed.substring(end + 1 + DELIMITER.length()).strip();

        // 2. Read name and version from the metadata
        Object parsed = new Yaml().load(metadata);
        if (!(parsed instanceof Map<?, ?> meta)) {
            throw new IllegalStateException("Skill " + name + ": metadata is not a key/value list");
        }
        String declaredName = meta.get("name") == null ? null : String.valueOf(meta.get("name"));
        String version = meta.get("version") == null ? null : String.valueOf(meta.get("version"));

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
        return new Skill(name, version, instructions);
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