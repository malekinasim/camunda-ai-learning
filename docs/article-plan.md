# Article plan: "Where should an AI agent sit in a business process?"

A practical article for the Data & AI Stockholm Substack, based on this project. Target: mid-November 2026.

## Core idea

- An AI agent works like a colleague inside a Camunda process.
- The same User Task can go to a person or to the agent, with no separate AI branch in the BPMN.
- A DMN decision table decides who does the task. The agent's autonomy is decided **outside** the agent: the decision is deterministic, auditable, and humans can change it.
- RAG and tools can help the agent do the task better, for example by reading a hiring policy. They do not decide whether the agent is allowed to act.
- The idea connects to the four-level autonomy spectrum discussed at Data & AI Stockholm 2026: human only, agent as assistant, agent with human approval, and fully autonomous agent.

## What already works

- `ResolveAssigneeListener` combines database delegation records with `resolve-assignee.dmn` to produce `finalAssignee`, which is either a person or `agent-bot`.
- `AgentTaskCompletionPoller` polls Tasklist for tasks assigned to `agent-bot`, scores the resume with Claude, and completes the task.
- The output contract is validated (score, decision, reason). Invalid model output fails the attempt instead of becoming a score of zero.
- The result is saved before the task is completed, so a retry does not call the model again.
- Known limits: the app runs as a single instance and does not guarantee exactly-once execution.

## Possible next step

Change the DMN output from an assignee to an **autonomy level**:

| Output | Meaning |
|---|---|
| `HUMAN` | A person does the task |
| `AGENT_WITH_APPROVAL` | The agent does the task and a person approves the result |
| `AGENT_AUTONOMOUS` | The agent completes the task alone (low-risk tasks only) |

Possible inputs: task type, risk level, the agent's confidence.

## Outline

1. The problem: companies want agents, but where in the process?
2. The autonomy spectrum.
3. Why BPMN + DMN is a natural place for this decision (lessons from years of workflow and task-inbox systems).
4. The demo: one task, routed by DMN to a person or to the agent. Code snippets.
5. Lessons learned and open questions: idempotency, the output contract, how the agent authenticates, keeping humans on final decisions.
