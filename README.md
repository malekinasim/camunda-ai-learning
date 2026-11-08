# Camunda task delegation: people and an AI worker

This learning project explores a simple idea: the same User Task can be assigned to a person or an AI worker without adding a separate AI branch to the BPMN diagram.

The current worker uses Tasklist's REST API. It does not open a browser or interact with the form. Browser automation is a possible next step.

## Two examples

| Process | Behavior |
|---|---|
| `resume-review-process` | A Service Task scores a resume with Claude. Scores of 50 or more continue to human review; lower scores end the process. |
| `hr-review-with-delegation-process` | A start execution listener resolves the assignee of one HR User Task using database delegation records and autonomy checks (small DMN decisions) declared on the task. |

## How delegation works

1. The User Task declares three input mappings: `taskType`, `delegatorRole` and `defaultAssignee`. The generic `resolve-assignee` start execution listener reads them and loads the active delegation records for that role and task type. A missing input fails the job with the process and element id; `DelegationWiringTest` also checks every BPMN file at build time.
2. A rule for the task's own type takes priority over a general rule (`ALL` or a null task type). Multiple active rules at the selected priority are rejected instead of choosing arbitrarily. A blank target is also rejected.
3. The listener evaluates the task's **before-checks**: the decision ids in the `taskAssigneeDecisions` input (for example `agent-requested, hr-risk`). Every check is a small DMN that returns `maxAutonomy`: `HUMAN`, `AGENT_WITH_APPROVAL` or `AGENT_AUTONOMOUS`. Each check gets all task variables, plus `agentRequested` (true when an active record hands this task to `agent-bot`). The strictest result wins. No checks, a failed check, or an unknown result count as `HUMAN`.
4. The assignee follows from that level: `HUMAN` gives `defaultAssignee`, anything else gives `agent-bot`. The listener returns `finalAssignee` and `autonomyBefore`. The BPMN assignment expression `=finalAssignee` uses the first.

After the agent has made its proposal, and before anything is applied, the poller evaluates the **after-checks** in `taskAfterDecisions` (for example `hr-outcome`). They see the task variables plus the proposal as `proposal`. The final level is the strictest of `autonomyBefore` and the after-checks, so an after-check can never raise the before limit:

| Final level | What the poller does |
|---|---|
| `AGENT_AUTONOMOUS` | Completes the task with the agent's result. |
| `AGENT_WITH_APPROVAL` | Sets the proposal as process variables, so the form shows it, then assigns the same task back to `defaultAssignee`, who approves or corrects it. |
| `HUMAN` | Assigns the task back to `defaultAssignee` without the proposal, so the person decides independently. |

Two implementation choices behind this:

- **The control component hands the task back, not the agent.** The poller reassigns through Zeebe (`newUserTaskAssignCommand`). In Tasklist, `agent-bot` may only assign tasks to itself, and that is kept on purpose: the agent cannot give work to other people (least privilege).
- **The proposal is set as process variables, not as a Tasklist draft.** A Tasklist draft is only visible to the user who saved it (`agent-bot`), so the person would see an empty form. The agent's original proposal also stays in the review attempt in the database.

This uses a **start execution listener**, not a creating User Task Listener or `correctAssignee`. The role, task type, default human assignee and checks are declared per task in the BPMN, so another User Task can use the same mechanism without new Java code.

Changing a database delegation record affects subsequent assignment evaluations without a BPMN redeploy. It does not reassign existing tasks. Checks are evaluated by decision id, so a newly deployed version of a check applies to running instances too.

## What the bot does

`AgentTaskCompletionPoller` runs with a 10-second fixed delay after the previous round finishes. It logs in to Tasklist, stores the session cookie, and searches for all active (`CREATED`) tasks assigned to `agent-bot`, of any task type and either implementation. For each task it picks an `AgentTaskExecutor` whose `supports(...)` accepts the task's `taskDefinitionId`. A task without a matching executor is handed back to its default assignee through Zeebe, with a warning in the log. If the task has no `defaultAssignee`, the error is logged and the task stays with `agent-bot`.

For each task the executor reads the skills declared on the task (`agentSkill`, `agentSkillsExtra`), runs the agent (`AgentService`) with them, and saves the validated result as a `ResumeReviewAttempt` keyed by task id before attempting completion. The saved attempt lets later rounds reuse the result rather than call Claude again. A successfully completed task is remembered so delayed search results do not trigger another execution.

Before completion, the worker reads the task again. It completes the task only if it is still CREATED and assigned to agent-bot; otherwise it logs the reason and skips it, and the saved result is not applied. If the task is already completed, it is recorded as the agent’s completion only when agent-bot is still the assignee.

### Skills

Each task declares a main skill and supporting skills as BPMN inputs, next to the autonomy checks:

```
agentSkill       = "resume-review"    # the task's instructions and output contract
agentSkillsExtra = "hiring-policy"    # extra rules the agent applies when relevant
```

- Skills live in `src/main/resources/skills/<name>/SKILL.md`: a frontmatter (`name`, `version`, `description`, and for a main skill `inputs`) followed by the instructions.
- A main skill also has `output.schema.json`. `AgentService` validates the model's output against it **in Java**: the skill describes the output, the controlling code enforces it. Invalid output fails the attempt; it never becomes a default result.
- The main skill's instructions go in the `system` field, followed by the supporting skills and when to use them. Only the inputs the main skill declares are sent, each in its own tag in the user message. Text in a resume is data, not instructions; this is a first layer against prompt injection, not a full guard.
- Every result stores the versions of all skills used (for example `resume-review@2,hiring-policy@1`) and the model.

Supporting skills are currently put in full into the system prompt, and the model decides when they apply. Loading them only on demand needs a tool-use loop (#32, #34).

### Output contract

The `resume-review` schema requires an integer `matchScore` from 0 to 100, a `verdict` (`APPROVED` or `REJECTED`), and a non-empty `reason`, with no other fields. The rule that a score of 50 or more means `APPROVED` is not in the schema: the `hr-consistency` after-check sends a result where score and verdict contradict each other to a person.

The delegated task receives:

| Variable | Value |
|---|---|
| `matchScore` | Integer from 0 to 100 |
| `verdict` | `APPROVED` or `REJECTED`, matching the form's radio options |
| `reason` | A separate explanation |

The older Service Task (`resume-review-process`) runs the same agent with `resume-review` and `hiring-policy` and returns the same three variables. It has no after-checks, so the score/verdict rule does not apply there; its gateway uses `matchScore`.

## Data model

`Job` stores a job posting and `Resume` stores a resume.

- `ResumeReviewRequest`: one row per process instance. It links the job and the resume, and has a status (`OPEN` / `COMPLETED`) and, later, the final attempt.
- `ResumeReviewAttempt`: one row per review of a task, by the agent or a person, unique per task id and reviewer. The agent saves its score, verdict and reason here before completing the task, with the skill versions and the model that produced them.

Human form submission writes process variables. **Recording human reviews as attempts is not implemented** (#23, #31).

## Versions and task type

The Java SDK is pinned to **8.7.39**. The checked-in Docker images for Zeebe, Tasklist, and Operate are **8.6.0**. This change preserves that existing setup; it does not claim the deployment is an all-8.7 cluster.

Job-worker-based User Tasks are deprecated as of Camunda 8.6. The HR User Task now uses the Camunda (Zeebe) User Task implementation (`<zeebe:userTask />`). Instances started from older process versions can still have job-worker-based tasks, so both kinds can exist at the same time.

The worker uses Tasklist v1 for both kinds; it does not need a second API:

- `POST /v1/tasks/search` returns both kinds. Each task has an `implementation` field: `JOB_WORKER` or `ZEEBE_USER_TASK`.
- `PATCH /v1/tasks/{id}/complete` completes both kinds. For a Zeebe User Task, Tasklist forwards the completion to the Zeebe REST API.

For that forwarding, Tasklist needs the Zeebe REST address in addition to the gRPC gateway address. Compose sets both:

```yaml
- CAMUNDA_TASKLIST_ZEEBE_GATEWAYADDRESS=zeebe:26500     # gRPC, job-worker-based tasks
- CAMUNDA_TASKLIST_ZEEBE_RESTADDRESS=http://zeebe:8080  # REST, Zeebe User Tasks
```

Without the REST address, completing a Zeebe User Task through Tasklist fails with HTTP 500 and a `ClientException` (`Connect to http://localhost:8089 ... Connection refused`), while job-worker-based tasks keep working. This was verified against the 8.6.0 images in this repository.

## Authentication

The local demo configures Tasklist's default account as `agent-bot`. The poller uses that account and cookie authentication, with CSRF prevention disabled in the local Compose setup. Credentials can be supplied through `TASKLIST_USERNAME` and `TASKLIST_PASSWORD`; the defaults match Compose.

API calls that encounter an expired session retry login at most once per request. HTTP calls have timeouts. This local cookie setup is not a claim that Camunda lacks machine-to-machine authentication. A future Browser Agent will need its own authorized browser session.

The default human assignee is `hr-employee`. Configure an actual human account and its permissions before testing the human path; the included bot account does not create that account automatically.

## Run locally

Prerequisites: Docker Compose, an Anthropic API key, and either Java 17 with Maven or Docker for the application build. No Maven Wrapper is included.

Set the key in your shell or local environment:

```bash
export ANTHROPIC_API_KEY="your-key"
```

Choose **one** application launch method.

### Option A: infrastructure in Docker, application in your IDE

```bash
docker compose up -d zeebe elasticsearch operate tasklist postgres
mvn spring-boot:run
```

Alternatively run `CamundaAiApplication` from your IDE. The application connects to services on localhost.

### Option B: everything in Docker

```bash
docker compose up -d --build
```

Compose sets `TASKLIST_BASE_URL=http://tasklist:8080` for the application container. Do not start a second application on port 9090 at the same time. Containers may take time to become ready; `depends_on` alone does not guarantee readiness.

Operate is at `http://localhost:8081`, Tasklist at `http://localhost:8082`, and the application at `http://localhost:9090`. Startup deploys BPMN, DMN, and form resources through `@Deployment`.

## Try a delegated review

```bash
curl -X POST http://localhost:9090/jobs -H 'Content-Type: application/json' \
  -d '{"title":"Backend Engineer","description":"Java and Spring Boot experience"}'

curl -X POST http://localhost:9090/resumes -H 'Content-Type: application/json' \
  -d '{"candidateName":"Example Candidate","resumeText":"Java backend developer with Spring Boot experience"}'

curl -X POST http://localhost:9090/delegations -H 'Content-Type: application/json' \
  -d '{"delegatorRole":"HR_EMPLOYEE","taskType":"hr-review","delegateTo":"agent-bot","startDate":"2026-11-01","endDate":"2026-12-31"}'

curl -X POST http://localhost:9090/start-hr-review -H 'Content-Type: application/json' \
  -d '{"jobId":1,"resumeId":1,"roleLevel":"JUNIOR"}'

curl http://localhost:9090/resume-reviews
```

Use the IDs returned by the create calls and a date range that includes today. Avoid creating overlapping delegation records at the same priority.

What to expect:

- `"roleLevel":"JUNIOR"` with a resume that matches the job: the agent completes the task.
- `"roleLevel":"SENIOR"`, or a resume the agent rejects: the task goes back to `hr-employee`, and the form shows the agent's proposal.
- No active delegation, or no `roleLevel`: the task goes to `hr-employee` directly.

The log line `Task …: autonomy before=…, after=…, final=…` shows how each decision was reached.

The original example is available through `POST /start-review` with `resumeText` and `jobDescription`.

## Tests

```bash
mvn test
```

Focused unit tests cover the scoring contract, delegation precedence, bounded session retries, reuse of saved decisions after completion failure, and the autonomy outcomes (complete, hand back with or without the proposal). They do not require a real model call or running Camunda cluster.

## Remaining limitations

- The poller handles the first 100 matching tasks per round. A persistently failing first page can delay later work; complete pagination is future work.
- Scoring is sequential and specific to the HR task. This is not yet a general task dispatcher.
- Multiple application instances require task-level locking and durable execution coordination.
- Assignment can change between a status check and completion; the status check is not atomic.
- Human result synchronization and comprehensive request validation are not implemented.
- The local database uses Hibernate `ddl-auto: update`; production schema changes need explicit migrations.

## Next step: browser execution

Keep the working API path as a baseline. A separate browser executor can authenticate, open the assigned form, inspect actual options, fill the fields, and verify submission. Prompts and DMN can provide the decision while the form supplies its current choices and validation behavior.

Version-specific task APIs, authentication, and UI routes should sit behind adapters. This makes future upgrades manageable, but does not make browser automation automatically compatible with every Camunda version.
