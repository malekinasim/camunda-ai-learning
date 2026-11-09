package com.nasim.camundaai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nasim.camundaai.gent.assignee.decisions.managment.DecisionUtils;
import com.nasim.camundaai.gent.assignee.entity.AutonomyLevel;
import com.nasim.camundaai.gent.assignee.entity.CheckResult;
import io.camunda.zeebe.client.ZeebeClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System-level eval (#58). The eval measures the agent; this measures the system: a wrong verdict is applied
 * without a person only if the autonomy checks give AGENT_AUTONOMOUS. Every saved eval run is passed through
 * the deployed DMNs (same DecisionUtils and the same strictest-wins rule as the app). No model calls.
 * Needs a running Zeebe with the DMNs deployed: RUN_SYSTEM_EVAL=true.
 * A check that fails or returns no valid level stops the measurement: in the app that fallback is HUMAN
 * (fail closed), but here it would look like a policy result, so no report is written.
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "RUN_SYSTEM_EVAL", matches = "true")
class SystemLevelEval {

    private static final String BPMN = "src/main/resources/hr-review-with-delegation.bpmn";
    private static final String TASK_ID = "Task_HrReview";
    private static final List<String> ROLE_LEVELS = List.of("JUNIOR", "SENIOR");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final DecisionUtils decisions = new DecisionUtils();
    private final Map<String, String> evaluatedDecisions = new TreeMap<>();       // added: decision id -> key and version used

    @Test
    void runEvalResultsThroughTheAutonomyChecks() throws Exception {
        // 1. The checks the HR task declares, read from the BPMN so this can never drift from the process
        Map<String, String> inputs = taskInputs(BPMN, TASK_ID);
        String beforeChecks = inputs.get("taskAssigneeDecisions");
        String afterChecks = inputs.get("taskAfterDecisions");
        assertNotNull(beforeChecks, "taskAssigneeDecisions not found on " + TASK_ID);
        assertNotNull(afterChecks, "taskAfterDecisions not found on " + TASK_ID);

        File[] files = new File("eval/results").listFiles(
                (dir, name) -> name.endsWith(".json") && !name.startsWith("system-level"));
        assertNotNull(files, "eval/results not found - run from the project root");
        Arrays.sort(files);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", LocalDateTime.now().toString());
        report.put("beforeChecks", beforeChecks);
        report.put("afterChecks", afterChecks);
        List<Map<String, Object>> rows = new ArrayList<>();

        try (ZeebeClient zeebe = zeebeClient()) {
            for (String roleLevel : ROLE_LEVELS) {
                // 2. Before: the owner asked for the agent (agentRequested), the role level decides the rest
                AutonomyLevel before = evaluate(zeebe, beforeChecks,                   // changed: failures stop the run
                        Map.of("agentRequested", true, "roleLevel", roleLevel), "before, " + roleLevel);

                for (File file : files) {
                    JsonNode result = mapper.readTree(file);
                    rows.add(evaluateFile(zeebe, file.getName(), result, roleLevel, before, afterChecks));
                }
            }
        }
        report.put("evaluatedDecisions", evaluatedDecisions);                    // added: which deployed versions were measured
        report.put("results", rows);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"));
        File out = new File("eval/results", "system-level_" + stamp + ".json");
        mapper.writeValue(out, report);

        rows.forEach(r -> System.out.printf("%-8s %-40s %-12s unreviewed wrong: %s, autonomous correct: %s, to a person: %s%n",
                r.get("roleLevel"), r.get("skillVersions"), r.get("split"),
                r.get("unreviewedWrong"), r.get("autonomousCorrect"), r.get("toAPerson")));
        System.out.println("written: " + out.getPath());
    }

    /** One eval report, one role level: what the system would have done with every run. */
    private Map<String, Object> evaluateFile(ZeebeClient zeebe, String fileName, JsonNode result,
                                             String roleLevel, AutonomyLevel before, String afterChecks) throws Exception {
        int runs = 0, unreviewedWrong = 0, autonomousCorrect = 0, toAPerson = 0;
        Map<String, Integer> finalLevels = new TreeMap<>();
        List<String> unreviewedWrongCases = new ArrayList<>();

        for (JsonNode c : result.get("cases")) {
            String id = c.get("golden").get("id").asText();
            String expected = c.get("golden").get("expected").asText();
            for (JsonNode run : c.get("runs")) {
                if (!run.get("failure").isNull()) continue;                  // no proposal, nothing to apply
                runs++;
                String verdict = run.get("verdict").asText();

                // 3. After: the proposal goes in as 'proposal', like in the poller
                Map<String, Object> proposal = Map.of("verdict", verdict, "matchScore", run.get("score").asInt());
                AutonomyLevel after = evaluate(zeebe, afterChecks,                       // changed: failures stop the run
                        Map.of("roleLevel", roleLevel, "proposal", proposal), fileName + " " + id);

                // 4. The after-checks can never raise the before limit (same rule as the poller)
                AutonomyLevel finalLevel = before.compareTo(after) <= 0 ? before : after;
                finalLevels.merge(finalLevel.name(), 1, Integer::sum);

                String actual = "NEEDS_REVIEW".equals(verdict) ? "HUMAN" : verdict;
                boolean wrong = !expected.equals(actual);
                if (finalLevel == AutonomyLevel.AGENT_AUTONOMOUS) {
                    if (wrong) {
                        unreviewedWrong++;
                        if (!unreviewedWrongCases.contains(id)) unreviewedWrongCases.add(id);
                    } else {
                        autonomousCorrect++;
                    }
                } else {
                    toAPerson++;
                }
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file", fileName);
        row.put("skillVersions", result.get("metadata").get("skillVersions").toString());
        row.put("split", result.get("split").asText());
        row.put("roleLevel", roleLevel);
        row.put("before", before.name());
        row.put("runs", runs);
        row.put("unreviewedWrong", unreviewedWrong);                         // wrong and applied without a person
        row.put("unreviewedWrongCases", unreviewedWrongCases);
        row.put("autonomousCorrect", autonomousCorrect);                     // right and applied without a person
        row.put("toAPerson", toAPerson);                                     // a person saw it, right or wrong
        row.put("finalLevels", finalLevels);
        return row;
    }

    /**
     * The app's own evaluation and combining rule, but a failed check, an empty list or an invalid level
     * fails the test instead of silently becoming HUMAN.
     */
    private AutonomyLevel evaluate(ZeebeClient zeebe, String checks, Map<String, Object> variables, String where)
            throws Exception {
        List<CheckResult> results = decisions.readDecisions(zeebe, mapper, checks, mapper.writeValueAsString(variables));
        List<String> problems = new ArrayList<>();
        if (results.isEmpty()) problems.add("no checks declared");
        for (CheckResult r : results) {
            if (r.failure() != null || r.response() == null) {
                problems.add(r.decisionId() + " failed: " + r.failure());
            } else if (Arrays.stream(AutonomyLevel.values()).noneMatch(l -> l.name().equals(r.output()))) {
                problems.add(r.decisionId() + " returned no valid level: " + r.output());
            } else {
                evaluatedDecisions.put(r.decisionId(),
                        "key " + r.response().getDecisionKey() + ", version " + r.response().getDecisionVersion());
            }
        }
        assertTrue(problems.isEmpty(), "Policy not evaluated (" + where + "): " + problems + ". No report written.");
        return decisions.combine(results);
    }

    /** Input mappings of one task, as name -> FEEL string literal without the quotes. */
    static Map<String, String> taskInputs(String bpmnPath, String taskId) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var doc = factory.newDocumentBuilder().parse(new File(bpmnPath));
        Element task = doc.getElementById(taskId);
        if (task == null) {                                                  // ids are not DTD ids, so search
            NodeList all = doc.getElementsByTagNameNS("*", "userTask");
            for (int i = 0; i < all.getLength() && task == null; i++) {
                Element e = (Element) all.item(i);
                if (taskId.equals(e.getAttribute("id"))) task = e;
            }
        }
        assertNotNull(task, "task " + taskId + " not found in " + bpmnPath);

        Map<String, String> inputs = new HashMap<>();
        NodeList list = task.getElementsByTagNameNS("http://camunda.org/schema/zeebe/1.0", "input");
        for (int i = 0; i < list.getLength(); i++) {
            Element input = (Element) list.item(i);
            String source = input.getAttribute("source").trim();             // e.g. ="hr-outcome, hr-consistency"
            if (source.startsWith("=\"") && source.endsWith("\"")) {
                inputs.put(input.getAttribute("target"), source.substring(2, source.length() - 1));
            }
        }
        return inputs;
    }

    /** Same addresses as the app, read from application.yaml. */
    private static ZeebeClient zeebeClient() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties props = Objects.requireNonNull(yaml.getObject());
        return ZeebeClient.newClientBuilder()
                .grpcAddress(URI.create(props.getProperty("camunda.client.zeebe.grpc-address")))
                .restAddress(URI.create(props.getProperty("camunda.client.zeebe.rest-address")))
                .usePlaintext()
                .build();
    }
}
