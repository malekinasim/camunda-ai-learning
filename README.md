# Camunda 8 + AI Agent: Task Delegation Between Humans and an AI Agent

A working example of an idea that goes beyond "AI calls an API inside a BPMN
step": **a Camunda 8 process where the same human task can be completed
either by a person or by an AI agent, decided dynamically, with zero
BPMN diagram changes when the rule changes.**

This repo intentionally keeps *both* an earlier, simpler version of the idea
and the current one, side by side, to show the evolution:

| | `resume-review-process` (the "before") | `hr-review-with-delegation-process` (the "after") |
|---|---|---|
| AI's role | A Service Task that scores a resume, then a human always reviews the result | The *same* User Task can be completed by a human **or** by the AI agent — decided per task, per delegation rule |
| Where the decision lives | A BPMN gateway (`matchScore >= 50 ?`) | A DMN decision table, evaluated by an execution listener before the task becomes visible |
| What changes when the business rule changes | Editing the BPMN | Adding/removing a row in a database table — no redeploy |

## The core idea: delegation without cluttering the diagram

The naive way to let an AI "cover" a human's tasks would be to add a
gateway to the BPMN: *"if delegated, go to the AI branch; otherwise go to
the human branch."* That gets messy fast (one gateway per delegatable
task) and mixes an operational, temporary decision ("Sara is on leave this
week") with the process's actual business logic.

Instead, `hr-review-with-delegation.bpmn` has **one** User Task
(`Task_HrReview`) with no visible branching at all:

```
[Start] → [Task_HrReview: HR reviews resume match] → [End]
```

The trick is a **Camunda 8 execution listener** (`eventType="start"`,
i.e. it fires the moment the task is *created*, before anyone can see it in
Tasklist):

1. `ResolveAssigneeListener` looks up whether there's an active delegation
   row for the `HR_EMPLOYEE` role today (`TaskDelegationRepository`).
2. It hands those raw facts (`hasActiveDelegation`, `delegateTo`,
   `defaultAssignee`) to `resolve-assignee.dmn` — a one-decision DMN table
   with `UNIQUE` hit policy. **This table is the only place the actual
   business rule lives.** Want a rule like "never delegate the final
   sign-off task"? That's a new row in the DMN table, zero Java changes.
3. The DMN's answer (`"agent-bot"` or the default human assignee) is set
   as the task's real assignee via `correctAssignee(...)` — a Camunda 8.7
   feature that lets a *creating* listener override a task's assignee
   before it's ever shown to anyone.

No gateway, no extra BPMN element, no redeploy when a delegation rule is
added — just a row in a table via `DelegationController`.

## Why the data model isn't "one flat table"

An earlier version of this stored `resumeText`/`jobDescription` directly on
each review request. That breaks down the moment you check **one job
description against 100 resumes** — the same job text would be duplicated
100 times. The model here is relational instead:

- `Job` — one row per job posting, created once.
- `Resume` — one row per candidate resume, created once.
- `ResumeReview` — the join: "this resume is being reviewed against this
  job." One process instance = one `ResumeReview` row, referencing a `Job`
  and a `Resume` by ID instead of duplicating their text. `matchScore` and
  `verdict` are filled in on this row when the task is completed — by
  whoever ends up owning it.

Whoever completes `Task_HrReview` — a human typing a score into a form, or
the AI agent running a prompt like an ATS — produces the exact same two
output variables (`matchScore`, `verdict`). The process doesn't know or
care which one happened.

## How a human without a UI... wait, how does a *headless AI agent* complete
a Camunda 8.7 User Task?

This turned out to be the hardest technical problem in the whole project,
and worth calling out on its own, because the answer isn't obvious and the
platform doesn't make it easy:

- Zeebe's User Task model is fundamentally built around a **human opening
  Tasklist and clicking a button** — unlike Service Tasks, Zeebe never
  *pushes* a User Task as a Job to a worker.
- The Zeebe Java client's `newUserTaskQuery()` (the seemingly obvious way
  for code to find its own tasks) is `@ExperimentalApi`, and in practice
  throws `MalformedResponseException` against this broker version.
- The actual working answer: the **Tasklist REST API** (`/v1/tasks/search`,
  `/v1/tasks/{id}/complete`) — a separate, stable component from the Zeebe
  broker, with its own Elasticsearch-backed index. `AgentTaskCompletionPoller`
  polls this API every 10 seconds for tasks assigned to the agent, runs
  `ClaudeScoringService` against them (the same scoring logic a human would
  eyeball), and completes them with the same `matchScore`/`verdict`
  contract a human's form submission would produce.
- One more platform quirk this surfaced: Tasklist's complete endpoint
  requires **the authenticated caller to literally be the task's
  assignee**. There is no built-in concept of a headless "service identity"
  distinct from a logged-in human — everything in Tasklist v1 assumes a
  person behind every login. The workaround here is to rename Tasklist's
  built-in default user to `agent-bot` (`CAMUNDA_TASKLIST_USERID` in
  `docker-compose.yml`), so the poller authenticates *as* the same identity
  the DMN table assigns tasks to. It's a genuine limitation of the
  platform's task model, not something this project could design around.

## Project structure

```
docker-compose.yml           Zeebe, Elasticsearch, Operate, Tasklist, Postgres, the app itself
src/main/resources/
  resume-review.bpmn         The "before": AI scores, gateway decides if a human reviews
  hr-review-with-delegation.bpmn   The "after": one User Task, assignee resolved dynamically
  hr-review-form.form        Camunda Form for the human path (shows job+resume, takes matchScore/verdict)
  resolve-assignee.dmn       The delegation business rule, as data, not code

src/main/java/com/nasim/camundaai/
  CamundaAiApplication.java  @Deployment(resources = {"classpath*:*.bpmn", "*.dmn", "*.form"})
  StartProcessController.java   POST /start-review (old process), POST /start-hr-review (new process)
  ai/
    ClaudeScoringService.java    Shared "score this resume against this job" logic
    AiAgentWorker.java           Job worker for the OLD process's call-ai-agent service task
  delegation/
    TaskDelegation.java, TaskDelegationRepository.java
    ResolveAssigneeListener.java   The execution listener described above
    DelegationController.java     POST/GET /delegations
  review/
    entity/  Job.java, Resume.java, ResumeReview.java
    repository/  JobRepository, ResumeRepository, ResumeReviewRepository
    controller/  JobController, ResumeController, ResumeReviewController
    agent/
      AgentTaskCompletionPoller.java   The Tasklist-API-based agent completion loop
```

## Running it

### 1. Set your API key

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

### 2. Start the stack

```bash
docker compose up -d
```

This brings up Zeebe, Elasticsearch, Operate (`:8081`), Tasklist (`:8082`),
and Postgres. Give it a minute or two.

### 3. Start the Spring Boot app

```bash
./mvnw spring-boot:run
```
(or run `CamundaAiApplication` from your IDE). Watch the log for the
`ZeebeDeploymentAnnotationProcessor` line confirming the BPMN/DMN/form
resources deployed.

### 4. Try the "before" process

```bash
curl -X POST http://localhost:9090/start-review \
  -H "Content-Type: application/json" \
  -d '{"resumeText": "...", "jobDescription": "..."}'
```

### 5. Try the "after" process, with delegation

```bash
# One job, one resume, created once
curl -X POST http://localhost:9090/jobs -H "Content-Type: application/json" \
  -d '{"title": "Backend Engineer", "description": "..."}'
curl -X POST http://localhost:9090/resumes -H "Content-Type: application/json" \
  -d '{"candidateName": "Ali", "resumeText": "..."}'

# Delegate HR_EMPLOYEE's tasks to the agent for a date range
curl -X POST http://localhost:9090/delegations -H "Content-Type: application/json" \
  -d '{"delegatorRole": "HR_EMPLOYEE", "delegateTo": "agent-bot", "startDate": "2026-09-15", "endDate": "2026-09-30"}'

# Start a review - lands on agent-bot because of the delegation above
curl -X POST http://localhost:9090/start-hr-review -H "Content-Type: application/json" \
  -d '{"jobId": 1, "resumeId": 1}'

# See the result once the poller (every 10s) completes it
curl http://localhost:9090/resume-reviews
```

Without an active delegation row, the same `/start-hr-review` call lands on
the default human assignee instead, visible and completable in Tasklist at
http://localhost:8082.

## API reference

| Endpoint | Purpose |
|---|---|
| `POST /jobs` | Create a job posting |
| `POST /resumes` | Create a candidate resume |
| `POST /start-review` | Start the old, AI-scores-then-human-reviews process |
| `POST /start-hr-review` | Start the delegation-aware process for a (job, resume) pair |
| `POST /delegations` | Create a delegation rule |
| `GET /delegations` | List delegation rules |
| `GET /resume-reviews` | See every review's current state (score, verdict, which process instance) |

## Future work / vision

The AI agent here polls Tasklist's REST API directly — the pragmatic,
actually-working version of a bigger idea: an agent that operates Tasklist
the same way a human would, through the UI itself (a "browser agent"),
with a proper dispatcher, a persistent execution store, lease-based
concurrency control so two agent workers never race on the same task, and
a real state machine for retries and reconciliation. That version would
also generalize past Tasklist's REST quirks by driving the same web UI a
human uses, and could extend to any Camunda version without depending on a
specific REST API's stability. It's out of scope for a learning project,
but it's the natural next step if this became more than a demo.
