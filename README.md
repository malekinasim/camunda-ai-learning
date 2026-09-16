# Camunda task delegation: people and an AI worker

This learning project explores a simple idea: the same User Task can be assigned to a person or an AI worker without adding a separate AI branch to the BPMN diagram.

The current worker uses Tasklist's REST API. It does not open a browser or interact with the form. Browser automation is a possible next step.

## Two examples

| Process | Behavior |
|---|---|
| `resume-review-process` | A Service Task scores a resume with Claude. Scores of 50 or more continue to human review; lower scores end the process. |
| `hr-review-with-delegation-process` | A start execution listener resolves the assignee of one HR User Task using database delegation records and a DMN decision. |

## How delegation works

1. `ResolveAssigneeListener` reads active delegation records for `HR_EMPLOYEE` and `hr-review`.
2. A rule for `hr-review` takes priority over a general rule (`ALL` or a null task type). Multiple active rules at the selected priority are rejected instead of choosing arbitrarily. A blank target is also rejected.
3. The listener evaluates `resolve-assignee.dmn` with `hasActiveDelegation`, `delegateTo`, and `defaultAssignee`.
4. It returns `finalAssignee`. The BPMN assignment expression `=finalAssignee` uses that value.

This uses a **start execution listener**, not a creating User Task Listener or `correctAssignee`. The role, task type, and default human assignee are currently Java constants. The DMN chooses the default user or delegate; it does not yet contain every possible business rule.

Changing a database delegation record affects subsequent assignment evaluations without a BPMN redeploy. It does not reassign existing tasks. Changing DMN logic requires deploying an updated decision.

## What the bot does

`AgentTaskCompletionPoller` runs with a 10-second fixed delay after the previous round finishes. It logs in to Tasklist, stores the session cookie, and searches for active `Task_HrReview` tasks assigned to `agent-bot`.

For each task it loads the linked `ResumeReview`, scores its resume against the job description, and saves the decision before attempting completion. The saved task ID lets later attempts reuse the result rather than call Claude again. A successfully completed task is remembered so delayed search results do not trigger another execution.

Before completion, the worker reads task state and assignment again. Unknown outcomes remain available for later checks; this is not a transaction across Tasklist and PostgreSQL. The current implementation is intended for **one application instance**. It does not provide distributed leases or exactly-once execution.

### Output contract

Claude returns a validated integer `score` from 0 to 100, a `decision` (`APPROVED` or `REJECTED`), and a non-empty `reason`. For this demo the decision must agree with the existing threshold: scores of 50 or more mean `APPROVED`.

The delegated task receives:

| Variable | Value |
|---|---|
| `matchScore` | Integer from 0 to 100 |
| `verdict` | `APPROVED` or `REJECTED`, matching the form's radio options |
| `reason` | A separate explanation |

The older Service Task keeps its existing contract: `verdict` contains the explanation. Invalid model output fails the attempt rather than silently becoming score zero.

## Data model

`Job` stores a job posting, `Resume` stores a resume, and `ResumeReview` links them to a process instance. The bot saves its result and task completion marker on the review row.

Human form submission writes process variables. **Synchronizing human results back to `ResumeReview` is not implemented.** Existing rows created before the bot task marker was added are not assumed to contain reusable validated decisions.

## Versions and task type

The Java SDK is pinned to **8.7.39**. The checked-in Docker images for Zeebe, Tasklist, and Operate are **8.6.0**. This change preserves that existing setup; it does not claim the deployment is an all-8.7 cluster.

The BPMN User Task currently has no `<zeebe:userTask />` extension and uses the job-worker-based implementation. The worker completes it through Tasklist v1. Migrating to native Camunda User Tasks requires checking form configuration and the supported completion API; it is not just a client version change.

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
  -d '{"delegatorRole":"HR_EMPLOYEE","taskType":"hr-review","delegateTo":"agent-bot","startDate":"2026-09-16","endDate":"2026-09-30"}'

curl -X POST http://localhost:9090/start-hr-review -H 'Content-Type: application/json' \
  -d '{"jobId":1,"resumeId":1}'

curl http://localhost:9090/resume-reviews
```

Use the IDs returned by the create calls and a date range that includes today. Avoid creating overlapping delegation records at the same priority. Without active delegation, the task goes to the configured human assignee.

The original example is available through `POST /start-review` with `resumeText` and `jobDescription`.

## Tests

```bash
mvn test
```

Focused unit tests cover the scoring contract, delegation precedence, bounded session retries, and reuse of saved decisions after completion failure. They do not require a real model call or running Camunda cluster.

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
