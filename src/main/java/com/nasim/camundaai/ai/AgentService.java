package com.nasim.camundaai.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A generic agent: one main skill (the task's instructions) plus supporting skills it uses when relevant.
 * The main skill's output schema is checked here, in Java: the skill describes the output,
 * the controlling code enforces it.
 */
@Component
public class AgentService {

    public record AgentResult(Map<String, Object> output, List<String> skillVersions, String model) { }

    private final SkillLoader skills;
    private final ClaudeClient claude;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public AgentService(SkillLoader skills, ClaudeClient claude) {
        this.skills = skills;
        this.claude = claude;
    }

    public AgentResult run(String mainSkillName, List<String> extraSkillNames,
                           Map<String, Object> taskVariables) throws Exception {
        Skill main = skills.load(mainSkillName);
        if (main.outputSchema() == null) {
            throw new IllegalStateException("Skill " + mainSkillName + " has no output.schema.json; it cannot be a main skill");
        }
        List<Skill> extras = extraSkillNames.stream().map(skills::load).toList();

        String response = claude.send(systemPrompt(main, extras), userMessage(main, taskVariables));
        Map<String, Object> output = validate(main, response);

        List<String> versions = new ArrayList<>();
        versions.add(main.versionId());
        extras.forEach(s -> versions.add(s.versionId()));
        return new AgentResult(output, versions, claude.model());
    }

    /** The main skill's instructions first; supporting skills follow, with when to use them. */
    static String systemPrompt(Skill main, List<Skill> extras) {
        StringBuilder system = new StringBuilder(main.instructions());
        if (!extras.isEmpty()) {
            system.append("\n\n# Additional skills you can use when relevant\n");
            for (Skill extra : extras) {
                system.append("\n## ").append(extra.name()).append("\n")
                        .append("Use when: ").append(extra.description()).append("\n\n")
                        .append(extra.instructions()).append("\n");
            }
        }
        return system.toString();
    }

    /** Only the inputs the main skill declares, each in its own tag. A missing input fails the attempt. */
    static String userMessage(Skill main, Map<String, Object> taskVariables) {
        StringBuilder data = new StringBuilder();
        for (String input : main.inputs()) {
            Object value = taskVariables.get(input);
            if (value == null) {
                throw new IllegalStateException("Skill " + main.name() + " needs input " + input + ", but the task has none");
            }
            data.append("<").append(input).append(">\n")
                    .append(value).append("\n")
                    .append("</").append(input).append(">\n");
        }
        return data.toString();
    }

    /** Invalid output fails the attempt; it never becomes a default result. */
    Map<String, Object> validate(Skill main, String response) throws Exception {
        JsonNode result = mapper.readTree(extractJson(response));
        JsonSchema schema = schemas.getSchema(main.outputSchema());
        Set<ValidationMessage> errors = schema.validate(result);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Output of " + main.versionId() + " does not match its schema: " + errors);
        }
        return mapper.convertValue(result, new TypeReference<>() { });
    }

    private static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Model response does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }
}