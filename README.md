# Camunda 8 + AI Agent — Learning Project

A minimal, working example of your idea: **BPMN (Camunda) orchestrates, an AI
agent makes a judgment call, and a human stays in the loop for the final
decision.**

## The process, in plain words

```
[Start] → [AI evaluates resume vs job] → is the match ≥ 50?
                                            ├─ yes → [Human reviews] → [End]
                                            └─ no  → [End, skipped]
```

Open `src/main/resources/resume-review.bpmn` in **Camunda Desktop Modeler**
(free download: https://camunda.com/download/modeler/) to see this visually
and to get comfortable editing BPMN yourself instead of hand-writing XML like
we did here.

## How the pieces fit together

| File | What it does |
|---|---|
| `docker-compose.yml` | Runs Zeebe (the engine) + Operate (a web UI to watch processes run) on your machine |
| `resume-review.bpmn` | The process definition: what happens, in what order |
| `AiAgentWorker.java` | The actual AI call — this is your "Agent" piece |
| `StartProcessController.java` | A REST endpoint to kick off a test run |
| `CamundaAiApplication.java` | Just boots Spring; the Camunda starter does the rest automatically |

## Concepts to understand before running this

- **Process instance**: one specific "run" of the BPMN diagram (e.g., one
  specific resume being evaluated). You can have thousands running at once.
- **Job**: a unit of work Zeebe hands out when a process instance reaches a
  Service Task. It doesn't know or care *what* does the work — that's the
  worker's job.
- **Job Worker**: your code, subscribed to a specific job "type" (here,
  `call-ai-agent`). Zeebe pushes matching jobs to it automatically.
- **Process variables**: the data flowing through a process instance
  (`resumeText`, `matchScore`, etc). Workers read some in, write some out.
- **Gateway**: a decision point. Our gateway reads `matchScore` (written by
  the AI worker) to decide whether a human ever needs to look at this one.

## Running it

### 1. Start the local Camunda 8 stack

```bash
docker compose up -d
```

Wait about a minute for everything to start, then check
http://localhost:8081 (Operate) loads in your browser.

### 2. Set your API key

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

### 3. Start the Spring Boot app

```bash
./mvnw spring-boot:run
```

Watch the logs — you should see it connect to Zeebe and deploy
`resume-review-process`.

### 4. Trigger a test run

```bash
curl -X POST http://localhost:9090/start-review \
  -H "Content-Type: application/json" \
  -d '{
    "resumeText": "Paste a short resume summary here",
    "jobDescription": "Paste a job posting here"
  }'
```

### 5. Watch it happen

Open http://localhost:8081 (Operate), find your process instance, and click
into it. You'll see it visually pass through each step — including pausing
at "Human: review AI's suggested tailored resume" if the AI's score was 50+.
That user task will sit there waiting for a human (this is intentional —
we haven't built a UI to complete it, but you could claim/complete it via
the Camunda Tasklist or the Zeebe API to finish the flow).

## Things to try next, in order of difficulty

1. **Change the threshold**: edit the `matchScore >= 50` condition in the
   BPMN gateway and see how behavior changes.
2. **Add a second AI step**: add another service task (e.g.
   `generate-tailored-resume`) that only runs after a good match — this
   is the "resume generation" half of your original idea.
3. **Add error handling**: what should happen if the Claude API call fails
   or times out? Zeebe supports BPMN error boundary events for exactly this.
4. **Replace the REST-trigger with something real**: instead of `curl`,
   imagine an email inbox connector or a job-board scraper starting the
   process automatically when a new posting appears.

## Why this matters for your idea

This tiny example already demonstrates the core architectural principle we
discussed: **BPM stays the orchestrator and owner of state** (auditable,
resumable, visible in Operate), while the **AI Agent is just one
interchangeable step** that can be swapped, retried, or bypassed by a human
— never the thing driving the whole process. That's the difference between
"an AI agent with some automation bolted on" (fragile, hard to govern) and
"a governed process with an AI step" (the pattern the market is actually
adopting, per what we found in the earlier research).
