# camunda-ai-learning: summary of decisions (20–21 Oct 2026)

Repo: github.com/malekinasim/camunda-ai-learning (reviewed at commit `b71d403`)

## 1. The article

- **Where:** Data & AI Stockholm Substack. The offer came from Vanessa Andersson; Azar (azar@dataaistockholm.com) coordinates.
- **Deadline:** full draft by mid-November 2026. Plan: code freeze on **8 November**, then one week for writing.
- **Length:** about 1,500 words, a few short code examples, one diagram. (No official length; archive examples are 1,200–1,700 words.)
- **Working title:** How much autonomy should an AI agent have?
- **Subtitle:** An AI colleague, with clearly defined limits
  (replaces "Where should an AI agent sit in a business process?", because the core of the article is *how much* the agent may do, not *where* it sits)
- **Audience:** data and AI people, not BPM experts. Use plain words ("workflow task", "decision table"). Introduce Camunda, BPMN and DMN once, in one sentence each.
- **Outline email to Azar:** drafted in Gmail (reply in Vanessa's thread, Vanessa in cc). It asks about angle, length and level of detail, and about mentioning Summit speakers. Status: ready to send.

### Structure sent to Azar
1. The problem: routine tasks accumulate in people's worklists. Where could agents help?
2. The autonomy question: from human-only work to autonomous execution (Summit discussions).
3. The agent as a colleague: the same task goes to a person or to an agent.
4. Setting limits: small decision tables control the agent's autonomy.
5. The example: resume review with Camunda, Java and the Claude API, showing the three autonomy levels.
6. Evaluation: the agent's decisions compared with human judgments, and the limits of a small test set.
7. Lessons learned: from this project and 15 years of building workflow systems.

Features not built and tested by writing time are presented as next steps.

### Extra sections in the working outline (not in the email)
- **Comparison with Camunda's AI Agent connector.** Camunda also supports human review, tool control and pre/post-processing, so the difference is not "Camunda has no controls". The difference is handing an *existing task* to a person or an agent, with autonomy decided by rules outside the agent. The DMN checks could also be used next to the connector. Name the connector version tested and link every claim.
- **What a production version would need**, about 100 words. See section 3.
- **The design journey**, as part of Lessons learned. See section 4.

## 2. Final autonomy design (her design)

**Levels:** `HUMAN` < `AGENT_WITH_APPROVAL` < `AGENT_AUTONOMOUS`

| Level | Behaviour |
|---|---|
| HUMAN | A person decides. If the agent has not started, it does not run. If it already produced output, the output is kept only as a draft. |
| AGENT_WITH_APPROVAL | The agent makes a proposal; a person approves or corrects that exact proposal. |
| AGENT_AUTONOMOUS | A valid result, inside the allowed scope, is applied automatically. |

**Two checkpoints**
1. **Before the agent runs:** may the agent start this work? Example checks: `hr-risk` (role level), `agent-trust` (agreement rate from evaluation).
2. **After the proposal, before the result is applied:** may the proposal be applied without approval? Example checks: `hr-outcome` (a reject needs approval), `hr-certainty` (a borderline score needs approval). Nothing is written to a final system, or sent, before this check.

**Each task declares its checks**, like it already declares `taskType` and `defaultAssignee`:
```
autonomyChecks.before = hr-risk, agent-trust
autonomyChecks.after  = hr-outcome, hr-certainty
```

**Combining the checks**
- Every check model returns the same output, `maxAutonomy`: the ceiling for this task and this action.
- The engine evaluates every declared model and takes the strictest: `finalAutonomy = min(before, after)`. The second checkpoint can never raise the first. This is least privilege applied to agents.
- "A rule can only lower autonomy" is true within one run. Changing or removing a policy in a later deployment can change the ceiling.
- The engine knows nothing about HR. A new task type needs only its own small DMN models for the autonomy decision; doing new *work* may still need a new skill, tool or adapter.

**Failure handling:** a policy error, an empty or unknown output, or no declared models all give `HUMAN`. Log the error so a broken policy does not stay hidden.

**Enforcement:** the component that applies the result checks the required approval; the agent cannot skip it. An approval is tied to that version of the proposal; a changed proposal needs a new approval.

**Audit:** for each run, store every check's result, its decision key and version, the reason for the limit, and who approved. Evaluating by decision id runs the latest deployed version, so keep the key.

**Worked example:** a resume for a senior role gives WITH_APPROVAL before the work. The agent rejects it, which gives WITH_APPROVAL after the work. A person confirms the rejection.

**Technical note:** Camunda 8.6 supports EvaluateDecision by id or key, and the project already calls DMN from Java (`newEvaluateDecisionCommand()` in the delegation listener).

## 3. Build vs. only mention

| Item | Plan |
|---|---|
| Three levels, two checkpoints, strictest wins, fallback to HUMAN | Build (#30) |
| Human approval path | Build (#31) |
| Poller and executor bugs | Fix (#14, #22) |
| Explicit fallback when the DMN has no matching row | Fix (#26) |
| Log of each check's result and version | Build, simple version |
| Approval tied to the proposal version | Mention |
| Central mandatory policies or a shared `policyProfile`; deploy-time check that the models exist | Mention, as production work |
| Tool permissions and OWASP: autonomy levels control the process, not tool access ("read resume" vs "send rejection email") | Mention |

## 4. Design journey (for Lessons learned)
1. Rules written straight into one or two big DMN tables, with HR rules inside. Not general.
2. Two generic DMNs with fixed inputs (SAFE/ADVERSE, CLEAR/BORDERLINE), the task-specific meaning defined in the skill. Part of the autonomy decision moved close to the agent, and the inputs were fixed.
3. A DRD (a fixed tree of small decisions). The tree cannot switch tables by task type.
4. **Final:** each task declares its own small check models, and the strictest result wins.

## 5. Code review findings (commit `b71d403`)

**Built:**
- one user task with a human assignee or `agent-bot`
- delegation plus DMN routing
- Claude scoring and output validation
- reusing the saved result on a retry, with limits

**Not built:**
- autonomy levels, check combining, the post-proposal check, human approval
- skill, tools, RAG, evaluation

The executor is still written for `Task_HrReview`, so a fully generic executor is not proven yet.

**Bugs:**
1. **Poller:** before completing, it re-reads the task but only checks `COMPLETED`. It still completes the task if it was reassigned to a person or cancelled. README line 31 claims the assignment is re-checked.
2. **ResumeScoringExecutor:** the review is found by `processInstanceKey`. `agentCompletionConfirmed=true` from an earlier task blocks a new task in the same process (line 40). This breaks loop-back or re-review.
3. **resolve-assignee.dmn:** some input combinations have no matching row, and the listener has no explicit fallback to a person.

**Precise wording for the article:**
- Saving the result avoids a second model call on a retry after a successful save. A crash between the model's answer and the save can still cause a repeat.
- Present evaluation as a *first* evaluation, not proof of trust. Report wrong rejections separately. Tie `agent-trust` to the model, prompt and skill version.
- A score's distance from the threshold is not the same as real model confidence.

## 6. GitHub changes made on 20 Oct
- **#14** rewritten: stop if the task is no longer `agent-bot`'s or not `CREATED`; one test per case. Moved to M2, priority high.
- **#30** rewritten with the full design in section 2. Done when the three demo runs work.
- **#45** new: make the README line about re-checking the assignment match the code.
- **#22** comment added with the executor bug and a proposed test (two agent tasks in one process).

## 7. Priority order (until 8 Nov)
1. #14 and #22 (bugs)
2. #30 and #31 (the core of the article)
3. Three demo runs: automatic, with approval, policy error that falls back to a person
4. Evaluation (#36, #37)
5. If time allows: skill and tools (#33, #34), RAG (#35), the Camunda connector spike (#42)

## 8. Open decisions
- Who computes `riskLevel`? Recommended: the process, with a small DMN per task type (not the skill).
- HR numbers: the borderline score range (suggested 40–70) and the trust threshold (suggested 80%).
- The connector comparison: her plan is a small hands-on test plus the docs.
- Swap the remaining HR-specific checks for others if they read too narrow.

## Related docs in the job-search project
- `claude/Article_Outline_DAIS_2026-10-20.md`: the detailed working outline (v4)
- `claude/Article_Outline_for_Azar.md`: the simple outline
- `claude/Article_DataAIStockholm_AgentInBusinessProcess_plan.md`: the original plan (15 Oct)
