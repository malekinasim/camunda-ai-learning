package com.nasim.camundaai.eval;


import java.util.List;

/** The golden dataset file: one job, many labeled resumes. */
record GoldenDataset(String taskType, String jobDescription, List<GoldenCase> cases) {

    /** expected: APPROVED, REJECTED or HUMAN (labeling-policy-v1). split: development or holdout. */
    record GoldenCase(String id, String category, String resumeText, String expected, String note, String split) { }
}