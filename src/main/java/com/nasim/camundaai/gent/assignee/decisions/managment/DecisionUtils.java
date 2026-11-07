package com.nasim.camundaai.gent.assignee.decisions.managment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nasim.camundaai.gent.assignee.entity.AutonomyLevel;
import com.nasim.camundaai.gent.assignee.entity.CheckResult;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.EvaluateDecisionResponse;
import org.slf4j.Logger;                                                        // added
import org.slf4j.LoggerFactory;                                                 // added
import org.springframework.stereotype.Component;                                // added
import org.springframework.util.StringUtils;
import java.util.Arrays;
import java.util.List;

@Component                                                                      // added: so the listener (and later the poller) can inject it
public class DecisionUtils {

    private static final Logger log = LoggerFactory.getLogger(DecisionUtils.class);   // added

    private  EvaluateDecisionResponse readDmnDecision(ZeebeClient zeebeClient, String decisionId,String variables){
        return  zeebeClient.newEvaluateDecisionCommand()
                .decisionId(decisionId)
                .variables(variables)
                .send()
                .join();
    }

    private List<CheckResult> readDecisions(ZeebeClient zeebeClient, ObjectMapper mapper, String taskDecisions, String variables) {
        if (!StringUtils.hasText(taskDecisions)) {
            return List.of();
        }

        return Arrays.stream(taskDecisions.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(decisionId -> {
                    try {
                        EvaluateDecisionResponse r = this.readDmnDecision(zeebeClient,decisionId,variables);
                        return new CheckResult(decisionId, r,mapper.readValue(r.getDecisionOutput(), String.class), null);
                    } catch (Exception e) {
                        log.error("Autonomy check {} failed; it counts as HUMAN", decisionId, e);   // fixed: a broken policy must not stay hidden
                        return new CheckResult(decisionId, null,null, e.getMessage());
                    }
                })
                .toList();
    }

    public AutonomyLevel makeFinalDecision(ZeebeClient zeebeClient,ObjectMapper mapper, String taskDecisions,  String variables){

        List<CheckResult> results=  this.readDecisions(zeebeClient,mapper,taskDecisions,variables);

        if (results.isEmpty()) return AutonomyLevel.HUMAN;

        if( results.stream().anyMatch(
                d -> d.failure() != null || d.response() == null
        )) return  AutonomyLevel.HUMAN;
        if(results.stream().anyMatch(d->AutonomyLevel.HUMAN.name().equals(d.output()))){
            return  AutonomyLevel.HUMAN;
        }else if(   results.stream().anyMatch(d->AutonomyLevel.AGENT_WITH_APPROVAL.name().equals(d.output())) ){
            return  AutonomyLevel.AGENT_WITH_APPROVAL;
        }else if(   results.stream().allMatch(d->AutonomyLevel.AGENT_AUTONOMOUS.name().equals(d.output())) ){
            return  AutonomyLevel.AGENT_AUTONOMOUS;
        }
        return  AutonomyLevel.HUMAN;
    }
}