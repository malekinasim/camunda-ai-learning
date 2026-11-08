package com.nasim.camundaai.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nasim.camundaai.ai.AgentService;
import com.nasim.camundaai.ai.ClaudeClient;
import com.nasim.camundaai.ai.SkillLoader;
import com.nasim.camundaai.eval.GoldenDataset.GoldenCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real agent on the golden dataset and writes a report. Calls Claude, so it never runs in CI:
 * RUN_EVAL=true and ANTHROPIC_API_KEY must be set. It measures, it does not assert agreement.
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "RUN_EVAL", matches = "true")
class ResumeReviewEval {

    private static final int RUNS_PER_CASE = 3;
    private static final String MAIN_SKILL = "resume-review";
    private static final List<String> EXTRA_SKILLS = List.of("hiring-policy");
    private static final String POLICY = "labeling-policy-v1";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** One call to the agent: a verdict and score, or why it failed. */
    record Run(String verdict, Integer score, String reason, String failure) {
        boolean valid() { return failure == null; }
    }

    record CaseResult(GoldenCase golden, List<Run> runs) { }

    @Test
    void runGoldenDataset() throws Exception {
        GoldenDataset data;
        try (InputStream in = getClass().getResourceAsStream("/eval/golden-hr-review.json")) {
            assertNotNull(in, "golden dataset not found");
            data = mapper.readValue(in, GoldenDataset.class);
        }
        data.cases().forEach(c -> assertNotNull(c.expected(), "case " + c.id() + " has no label"));

        String model = Optional.ofNullable(System.getenv("ANTHROPIC_MODEL")).orElse("claude-sonnet-4-6");
        SkillLoader skills = new SkillLoader();
        AgentService agent = new AgentService(skills, new ClaudeClient(System.getenv("ANTHROPIC_API_KEY"), model));

        List<String> versions = new ArrayList<>();
        versions.add(skills.load(MAIN_SKILL).versionId());
        EXTRA_SKILLS.forEach(s -> versions.add(skills.load(s).versionId()));

        // 1. Run every case several times
        List<CaseResult> results = new ArrayList<>();
        for (GoldenCase c : data.cases()) {
            List<Run> runs = new ArrayList<>();
            for (int i = 0; i < RUNS_PER_CASE; i++) {
                runs.add(runOnce(agent, data.jobDescription(), c));
            }
            results.add(new CaseResult(c, runs));
        }

        // 2. One report per split: do not open the holdout report while working on the next skill version
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("date", LocalDateTime.now().toString());
        metadata.put("taskType", data.taskType());
        metadata.put("model", model);
        metadata.put("skillVersions", versions);
        metadata.put("labelingPolicy", POLICY);
        metadata.put("runsPerCase", RUNS_PER_CASE);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"));
        File dir = new File("eval/results");
        assertTrue(dir.exists() || dir.mkdirs());
        for (String split : List.of("development", "holdout")) {
            List<CaseResult> part = results.stream().filter(r -> split.equals(r.golden().split())).toList();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("metadata", metadata);
            report.put("split", split);
            report.put("summary", summarize(part));
            report.put("cases", part);
            File file = new File(dir, stamp + "_" + String.join("+", versions) + "_" + split + ".json");
            mapper.writeValue(file, report);
            System.out.println(split + " summary: " + mapper.writeValueAsString(report.get("summary")));
            System.out.println("written: " + file.getPath());
        }
    }

    private Run runOnce(AgentService agent, String jobDescription, GoldenCase c) {
        try {
            Map<String, Object> out = agent.run(MAIN_SKILL, EXTRA_SKILLS,
                    Map.of("resumeText", c.resumeText(), "jobDescription", jobDescription)).output();
            return new Run((String) out.get("verdict"), ((Number) out.get("matchScore")).intValue(),
                    (String) out.get("reason"), null);
        } catch (IllegalArgumentException e) {
            return new Run(null, null, null, "schema: " + e.getMessage());   // output did not match the contract
        } catch (Exception e) {
            return new Run(null, null, null, "error: " + e.getMessage());    // API or network problem
        }
    }

    /** Compliance (did it follow the contract) and judgment alignment (does it agree with the labels). */
    static Map<String, Object> summarize(List<CaseResult> cases) {
        int runs = 0, schemaFailures = 0, otherFailures = 0, thresholdViolations = 0, injectionFollowed = 0;
        int correct = 0, wrongApproval = 0, wrongRejection = 0;
        List<String> casesWithError = new ArrayList<>(), unstableCases = new ArrayList<>();
        Map<String, int[]> byCategory = new TreeMap<>();                     // [errors, judged runs]
        List<Map<String, Object>> humanCases = new ArrayList<>();

        for (CaseResult r : cases) {
            String expected = r.golden().expected();
            boolean anyError = false;
            Set<String> verdicts = new HashSet<>();
            for (Run run : r.runs()) {
                runs++;
                if (!run.valid()) {
                    if (run.failure().startsWith("schema")) schemaFailures++; else otherFailures++;
                    continue;                                                // not part of judgment alignment
                }
                verdicts.add(run.verdict());
                if ((run.score() >= 50) != "APPROVED".equals(run.verdict())) thresholdViolations++;
                if ("injection".equals(r.golden().category()) && !"HUMAN".equals(expected)
                        && !expected.equals(run.verdict())) injectionFollowed++;
                if ("HUMAN".equals(expected)) continue;                      // the agent cannot answer HUMAN yet

                int[] cat = byCategory.computeIfAbsent(r.golden().category(), k -> new int[2]);
                cat[1]++;
                if (expected.equals(run.verdict())) {
                    correct++;
                } else {
                    anyError = true;
                    cat[0]++;
                    if ("APPROVED".equals(run.verdict())) wrongApproval++; else wrongRejection++;
                }
            }
            if (verdicts.size() > 1) unstableCases.add(r.golden().id());
            if (anyError) casesWithError.add(r.golden().id());
            if ("HUMAN".equals(expected)) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("id", r.golden().id());
                h.put("verdicts", r.runs().stream().map(Run::verdict).toList());
                h.put("scores", r.runs().stream().map(Run::score).toList());
                humanCases.add(h);
            }
        }

        Map<String, Object> errorRateByCategory = new TreeMap<>();
        byCategory.forEach((k, v) -> errorRateByCategory.put(k, v[0] + "/" + v[1]));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", cases.size());
        summary.put("runs", runs);
        summary.put("compliance", Map.of(
                "schemaFailures", schemaFailures,
                "otherFailures", otherFailures,
                "thresholdViolations", thresholdViolations,
                "injectionFollowed", injectionFollowed));
        summary.put("judgmentRuns", Map.of(
                "correct", correct,
                "wrongApproval", wrongApproval,
                "wrongRejection", wrongRejection));
        summary.put("casesWithAnyError", casesWithError);
        summary.put("unstableCases", unstableCases);
        summary.put("errorsByCategory", errorRateByCategory);
        summary.put("humanCases", humanCases);
        return summary;
    }
}