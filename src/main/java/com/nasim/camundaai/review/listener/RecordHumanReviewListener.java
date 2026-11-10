package com.nasim.camundaai.review.listener;

import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt.AttemptStatus;
import com.nasim.camundaai.review.entity.ResumeReviewAttempt.ProposalReview;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.service.ResumeReviewAttemptService;
import com.nasim.camundaai.review.service.ResumeReviewRequestService;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * End execution listener on the HR review task (#31, #23). When a person completes the task, their decision is
 * stored as their own attempt, next to the agent's proposal, together with what they did with that proposal.
 * The agent's own completion is recorded by the poller, so it is skipped here.
 */
@Component
public class RecordHumanReviewListener {

    static final String JOB_TYPE = "record-human-review";
    static final String AGENT_ASSIGNEE = "agent-bot";
    private static final Logger log = LoggerFactory.getLogger(RecordHumanReviewListener.class);

    private final TasklistClient tasklist;
    private final ResumeReviewRequestService requests;
    private final ResumeReviewAttemptService attempts;

    public RecordHumanReviewListener(TasklistClient tasklist, ResumeReviewRequestService requests,
                                     ResumeReviewAttemptService attempts) {
        this.tasklist = tasklist;
        this.requests = requests;
        this.attempts = attempts;
    }

    @JobWorker(type = JOB_TYPE, fetchVariables = {"verdict", "matchScore", "reason"})
    public void recordHumanReview(ActivatedJob job) throws Exception {
        long processInstanceKey = job.getProcessInstanceKey();
        Optional<ResumeReviewRequest> request = requests.findByProcessInstanceKey(processInstanceKey);
        if (request.isEmpty()) {
            log.info("No review request for process instance {}; nothing to record", processInstanceKey);
            return;                                                          // not started through /start-hr-review
        }

        // 1. Which task and who completed it: the job does not say, Tasklist does
        TasklistTask task = theTask(processInstanceKey, job.getElementId());
        if (AGENT_ASSIGNEE.equals(task.assignee())) {
            return;                                                          // the poller records the agent's own completion
        }
        if (task.assignee() == null || task.assignee().isBlank()) {
            throw new IllegalStateException("Task " + task.id() + " was completed without an assignee");
        }
        if (attempts.find(task.id(), task.assignee()).isPresent()) {
            // A retry: the decision is saved, but closing the request may have failed last time.
            // Closing is idempotent, so do it again instead of returning with the request still OPEN.
            requests.closeWithLatestCompletedAttempt(processInstanceKey);
            return;
        }

        // 2. What the person submitted in the form
        Map<String, Object> submitted = job.getVariablesAsMap();
        String verdict = (String) submitted.get("verdict");
        if (!(submitted.get("matchScore") instanceof Number score) || verdict == null) {
            throw new IllegalStateException("Task " + task.id() + " completed without verdict and matchScore");
        }

        // 3. Compare with the proposal the person was shown, as stored, not as process variables
        ProposalReview review = compare(attempts.find(task.id(), AGENT_ASSIGNEE), verdict, score);

        // 4. The person's decision is the final result
        attempts.saveHumanDecision(task.id(), task.assignee(), request.get(),
                score.doubleValue(), verdict, (String) submitted.get("reason"), review);
        requests.closeWithLatestCompletedAttempt(processInstanceKey);
        log.info("Task {} decided by {}: verdict={}, proposal={}", task.id(), task.assignee(), verdict, review);
    }

    /** The one HR task of this process instance. A process with a loop back to the task needs another way. */
    private TasklistTask theTask(long processInstanceKey, String elementId) throws Exception {
        List<TasklistTask> tasks = tasklist.searchTasks(Map.of(
                        "processInstanceKey", String.valueOf(processInstanceKey),
                        "taskDefinitionId", elementId)).stream()
                .filter(t -> !"CANCELED".equals(t.taskState()))
                .toList();
        if (tasks.size() != 1) {
            throw new IllegalStateException("Expected one " + elementId + " task in process instance "
                    + processInstanceKey + ", found " + tasks.size());
        }
        return tasks.get(0);
    }

    /**
     * What the person did with the agent's proposal. Only a RESULT_SAVED proposal was shown to them
     * (AGENT_WITH_APPROVAL); a withdrawn one was not (HUMAN, #46), and then there is nothing to compare.
     */
    static ProposalReview compare(Optional<ResumeReviewAttempt> agent, String verdict, Number score) {
        if (agent.isEmpty() || !AttemptStatus.RESULT_SAVED.equals(agent.get().getStatus())) {
            return ProposalReview.NO_PROPOSAL;
        }
        if (!verdict.equals(agent.get().getVerdict())) {
            return ProposalReview.VERDICT_CHANGED;
        }
        if (Math.round(score.doubleValue()) != Math.round(agent.get().getMatchScore())) {
            return ProposalReview.SCORE_CHANGED;
        }
        return ProposalReview.ACCEPTED;
    }
}
