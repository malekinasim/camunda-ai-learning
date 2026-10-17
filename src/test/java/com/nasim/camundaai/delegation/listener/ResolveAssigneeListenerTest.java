package com.nasim.camundaai.delegation.listener;

import com.nasim.camundaai.delegation.entity.TaskDelegation;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResolveAssigneeListenerTest {
    private TaskDelegation rule(String type, String target) {
        return new TaskDelegation("HR_EMPLOYEE", type, target, LocalDate.now(), null);
    }
    @Test void specificRuleWinsOverGeneralRules() {
        var specific = rule("hr-review", "person");
        assertSame(specific, ResolveAssigneeListener.selectDelegation("hr-review",
                List.of(rule("ALL", "bot"), rule(null, "other"), specific)).orElseThrow());
    }
    @Test void specificityDependsOnTheTaskTypeAskedFor() {
        // The same rules, asked for another task type: the hr-review rule is not
        // specific for it, so only the general rule should apply.
        var general = rule("ALL", "bot");
        assertSame(general, ResolveAssigneeListener.selectDelegation("contract-approval",
                List.of(general)).orElseThrow());
    }
    @Test void ambiguousRulesAreRejected() {
        assertThrows(IllegalStateException.class, () -> ResolveAssigneeListener.selectDelegation("hr-review",
                List.of(rule("hr-review", "a"), rule("hr-review", "b"))));
        assertThrows(IllegalStateException.class, () -> ResolveAssigneeListener.selectDelegation("hr-review",
                List.of(rule("ALL", "a"), rule(null, "b"))));
    }
    @Test void noRuleAndBlankTargetAreHandled() {
        assertTrue(ResolveAssigneeListener.selectDelegation("hr-review", List.of()).isEmpty());
        assertThrows(IllegalStateException.class, () -> ResolveAssigneeListener.selectDelegation("hr-review",
                List.of(rule("hr-review", " "))));
    }
    @Test void missingInputFailsWithTheTaskName() {
        var e = assertThrows(IllegalStateException.class, () ->
                ResolveAssigneeListener.requireDeclared("taskType", null, "my-process/Task_X"));
        assertTrue(e.getMessage().contains("my-process/Task_X"));
        assertTrue(e.getMessage().contains("taskType"));
        assertThrows(IllegalStateException.class, () ->
                ResolveAssigneeListener.requireDeclared("delegatorRole", "  ", "p/t"));
    }
}
