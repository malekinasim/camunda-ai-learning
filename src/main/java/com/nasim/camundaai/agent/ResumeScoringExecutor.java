package com.nasim.camundaai.agent;

import com.nasim.camundaai.agent.tasklist.TasklistClient;                          // added
import com.nasim.camundaai.ai.AgentService;                                         // changed: was ClaudeScoringService
import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.entity.ResumeReviewRequest;
import com.nasim.camundaai.review.service.ResumeReviewAttemptService;
import com.nasim.camundaai.review.service.ResumeReviewRequestService;
import org.springframework.stereotype.Component;

import java.util.Arrays;                                                            // added
import java.util.LinkedHashMap;
import java.util.List;                                                              // added
import java.util.Map;
import java.util.Optional;

/** Runs the agent with the skills the process declares on the task, and stores the result as a review attempt. */
@Component
public class ResumeScoringExecutor implements AgentTaskExecutor {

    private static final String Task_HR_REVIEW_DEFINITION_ID = "Task_HrReview";
    private final AgentService agent;                                               // changed
    private final TasklistClient tasklist;                                          // added
    private final ResumeReviewRequestService resumeReviewRequestService;
    private final ResumeReviewAttemptService resumeReviewAttemptService;

    public ResumeScoringExecutor(AgentService agent, TasklistClient tasklist,       // changed
                                 ResumeReviewRequestService resumeReviewRequestService,
                                 ResumeReviewAttemptService resumeReviewAttemptService) {
        this.agent = agent;
        this.tasklist = tasklist;
        this.resumeReviewRequestService = resumeReviewRequestService;
        this.resumeReviewAttemptService = resumeReviewAttemptService;
    }

    @Override
    public boolean supports(String taskDefinitionId) {
        return taskDefinitionId.equals(Task_HR_REVIEW_DEFINITION_ID);
    }

    @Override
    public AgentTaskResult execute(AgentTask task) throws Exception {
        ResumeReviewRequest request = resumeReviewRequestService.getByProcessInstanceKey(task.processInstanceKey());
        Optional<ResumeReviewAttempt> saved = resumeReviewAttemptService.find(task.id(), task.assignee());
        ResumeReviewAttempt attempt = saved.isPresent()
                ? resumeReviewAttemptService.reactivateIfWithdrawn(saved.get())
                : scoreAndSave(task, request);
        return toResult(attempt);
    }

    @Override
    public void markAsAgentCompleteTask(AgentTask current, String assignee) {
        // the poller calls this only when agent-bot completed the task (now or earlier)
        resumeReviewAttemptService.markCompleted(current.id(), assignee);
        resumeReviewRequestService.closeWithLatestCompletedAttempt(current.processInstanceKey());
    }

    @Override
    public void markAsWithdrawn(AgentTask current, String assignee) {
        resumeReviewAttemptService.markWithdrawn(current.id(), assignee);
    }

    /** Calls the agent once, with the skills declared on the task, and saves the result before completion. */
    private ResumeReviewAttempt scoreAndSave(AgentTask task, ResumeReviewRequest request) throws Exception {   // changed
        // 1. The task's variables: the BPMN inputs (agentSkill, agentSkillsExtra) and the process data
        Map<String, Object> variables = tasklist.searchTaskVariables(task.id(), List.of());
        String mainSkill = (String) variables.get("agentSkill");
        List<String> extraSkills = split(variables.get("agentSkillsExtra"));

        // 2. The generic agent: loads the skills, sends the declared inputs, validates the output
        AgentService.AgentResult result = agent.run(mainSkill, extraSkills, variables);

        // 3. Store it; the output already matched output.schema.json, so these casts are safe
        Map<String, Object> out = result.output();
        return resumeReviewAttemptService.saveResult(task.id(), task.assignee(), request,
                ((Number) out.get("matchScore")).doubleValue(),
                (String) out.get("verdict"),
                (String) out.get("reason"),
                String.join(",", result.skillVersions()), result.model());
    }

    /** "hiring-policy, other" -> ["hiring-policy", "other"]; missing -> no supporting skills. */
    static List<String> split(Object declared) {                                    // added
        if (!(declared instanceof String list) || list.isBlank()) {
            return List.of();
        }
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Only a COMPLETED attempt means "done"; a withdrawn one was reactivated before this. */
    private AgentTaskResult toResult(ResumeReviewAttempt attempt) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("matchScore", attempt.getMatchScore().intValue());
        variables.put("verdict", attempt.getVerdict());
        variables.put("reason", attempt.getReason());
        return new AgentTaskResult(variables,
                ResumeReviewAttempt.AttemptStatus.COMPLETED.equals(attempt.getStatus()));
    }
}