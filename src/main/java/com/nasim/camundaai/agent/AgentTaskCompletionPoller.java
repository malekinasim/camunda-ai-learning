package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.tasklist.TasklistClient;
import com.nasim.camundaai.agent.tasklist.TasklistTask;
import com.nasim.camundaai.ai.ClaudeScoringService;
import com.nasim.camundaai.review.entity.ResumeReview;
import com.nasim.camundaai.review.repository.ResumeReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class AgentTaskCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskCompletionPoller.class);
    private static final String AGENT_ASSIGNEE = "agent-bot";
    private static final String TASK_DEFINITION_ID = "Task_HrReview";

    private final TasklistClient tasklist;
    private final ResumeReviewRepository resumeReviewRepository;
    private final ClaudeScoringService claudeScoringService;

    public AgentTaskCompletionPoller(TasklistClient tasklist,
                                     ResumeReviewRepository resumeReviewRepository,
                                     ClaudeScoringService claudeScoringService) {
        this.tasklist = tasklist;
        this.resumeReviewRepository = resumeReviewRepository;
        this.claudeScoringService = claudeScoringService;
    }

    @Scheduled(fixedDelay = 10000)
    public void completeAgentTasks() {
        try {
            for (TasklistTask task : tasklist.searchTasks(AGENT_ASSIGNEE, "CREATED", TASK_DEFINITION_ID)) {
                try {
                    completeOne(task);
                } catch (Exception e) {
                    log.error("Failed to complete task {} for agent-bot", task.id(), e);
                }
            }
        } catch (Exception e) {
            log.error("agent-bot poller failed this round", e);
        }
    }

    private void completeOne(TasklistTask task) throws Exception {
        if (!TASK_DEFINITION_ID.equals(task.taskDefinitionId())) {
            return;
        }
        ResumeReview review = resumeReviewRepository.findByProcessInstanceKey(task.processInstanceKey())
                .orElseThrow(() -> new IllegalStateException(
                        "No ResumeReview found for process instance " + task.processInstanceKey()));

        if (task.id().equals(review.getAgentTaskId()) && review.isAgentCompletionConfirmed()) {
            return; // Tasklist's search index may still show a completed task.
        }
        if (!task.id().equals(review.getAgentTaskId())) {
            ClaudeScoringService.ScoreResult result = claudeScoringService.evaluateMatch(
                    review.getResume().getResumeText(), review.getJob().getDescription());
            review.setMatchScore((double) result.score());
            review.setVerdict(result.decision());
            review.setReason(result.reason());
            review.setAgentTaskId(task.id());
            review.setAgentCompletionConfirmed(false);
            resumeReviewRepository.save(review);
        }

        // Recheck after scoring and before every completion attempt. A timeout
        // on the previous round may have happened after the engine completed it.
        TasklistTask current = tasklist.getTask(task.id());
        if ("COMPLETED".equals(current.taskState())) {
            review.setAgentCompletionConfirmed(true);
            resumeReviewRepository.save(review);
            return;
        }
        if (!"CREATED".equals(current.taskState()) || !AGENT_ASSIGNEE.equals(current.assignee())) {
            return;
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("matchScore", review.getMatchScore().intValue());
        variables.put("verdict", review.getVerdict());
        variables.put("reason", review.getReason());
        tasklist.completeTask(task.id(), variables);

        review.setAgentCompletionConfirmed(true);
        resumeReviewRepository.save(review);
        log.info("agent-bot completed task {} with matchScore={} verdict={}",
                task.id(), review.getMatchScore().intValue(), review.getVerdict());
    }
}