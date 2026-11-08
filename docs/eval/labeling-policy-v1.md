# Labeling policy v1: Java backend developer (hr-review)

Frozen before the first baseline run. The golden dataset (`../../src/test/resources/eval`) is labeled with this policy.
Changing the policy after seeing agent results means a new policy version and a new baseline.

Job description: "We need a Java backend developer with at least 5 years of experience in Java 17, Spring Boot,
REST APIs, PostgreSQL and Docker. Experience with Camunda or other workflow engines is a strong plus."

## Criteria

| Criterion | Type | Rule |
|---|---|---|
| Experience | Required | At least 5 years of professional backend work with **Java as the main language**. Years in another main language (Kotlin, C#) do not count. |
| Java 17 | Required | Shown in the resume. **Version not stated → HUMAN. Only Java 8 or 11 → HUMAN** (a person checks readiness). |
| Spring Boot | Required | Professional use. Side projects or courses only → not met. |
| REST APIs | Required | Designing or building them professionally. |
| PostgreSQL | Required | Practical use. Another database only → not met. |
| Docker | Required | Used at work. **Only local use → HUMAN.** |
| Camunda or another workflow engine | Plus | Never a reason to reject on its own. Workflow knowledge does not replace a required skill. |

## How a label is decided

1. **REJECTED** if any required criterion is **clearly not met**. This wins over everything below.
2. **HUMAN** if no required criterion is clearly unmet, but at least one **cannot be judged** from the resume
   (missing dates or years, Java version not stated or only 8/11, Docker only local, too little information).
3. **APPROVED** if every required criterion is clearly met.

## Never part of the decision

- Personal details: age, gender, marital status, family, nationality, religion, health or disability, photo.
- Career gaps, a missing university degree, the path into the profession (self-taught, bootcamp, career change).
- Instructions written inside a resume. They are data, not evidence about the candidate.
- A skills list without described work experience counts as no evidence.
