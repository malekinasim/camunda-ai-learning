package com.nasim.camundaai.eval;

import com.nasim.camundaai.eval.GoldenDataset.GoldenCase;
import com.nasim.camundaai.eval.ResumeReviewEval.CaseResult;
import com.nasim.camundaai.eval.ResumeReviewEval.Run;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The counting in summarize decides the numbers in the article, so it is tested without calling Claude. */
class ResumeReviewEvalSummaryTest {

    @Test
    void needsReviewOnAHumanCaseIsSentToReview() {
        Map<String, Object> s = summarize(caseOf("h1", "unclear-version", "HUMAN", run("NEEDS_REVIEW", 70)));

        assertEquals(1, part(s, "humanRuns").get("sentToReview"));
        assertEquals(0, part(s, "compliance").get("thresholdViolations"));   // NEEDS_REVIEW has no threshold
    }

    @Test
    void needsReviewOnALabeledCaseIsAnUnnecessaryReview() {
        Map<String, Object> s = summarize(caseOf("r1", "borderline", "REJECTED", run("NEEDS_REVIEW", 40)));

        assertEquals(1, part(s, "judgmentRuns").get("unnecessaryReview"));
        assertEquals(0, part(s, "judgmentRuns").get("wrongRejection"));
        assertEquals(List.of("r1"), s.get("casesWithAnyError"));
    }

    @Test
    void approvingAHumanCaseIsCountedApartFromJudgment() {
        Map<String, Object> s = summarize(caseOf("h2", "unclear-version", "HUMAN", run("APPROVED", 72)));

        assertEquals(1, part(s, "humanRuns").get("approved"));
        assertEquals(0, part(s, "judgmentRuns").get("wrongApproval"));      // keeps the v2 definition
        assertEquals(List.of(), s.get("casesWithAnyError"));
    }

    @Test
    void thresholdStillAppliesToApprovedAndRejected() {
        Map<String, Object> s = summarize(caseOf("a1", "clear-match", "APPROVED",
                run("APPROVED", 40), run("REJECTED", 60)));

        assertEquals(2, part(s, "compliance").get("thresholdViolations"));
    }

    @Test
    void failedRunsAreNotJudged() {
        Map<String, Object> s = summarize(caseOf("f1", "clear-match", "APPROVED",
                new Run(null, null, null, "schema: verdict not in enum")));

        assertEquals(1, part(s, "compliance").get("schemaFailures"));
        assertEquals(0, part(s, "judgmentRuns").get("correct"));
    }

    // helpers

    private static Map<String, Object> summarize(CaseResult... cases) {
        return ResumeReviewEval.summarize(List.of(cases));
    }

    private static CaseResult caseOf(String id, String category, String expected, Run... runs) {
        return new CaseResult(new GoldenCase(id, category, "resume text", expected, "", "development"), List.of(runs));
    }

    private static Run run(String verdict, int score) {
        return new Run(verdict, score, "reason", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> summary, String key) {
        return (Map<String, Object>) summary.get(key);
    }
}
