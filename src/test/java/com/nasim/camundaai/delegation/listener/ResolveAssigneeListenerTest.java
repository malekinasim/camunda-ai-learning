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
        assertSame(specific, ResolveAssigneeListener.selectDelegation(
                List.of(rule("ALL", "bot"), rule(null, "other"), specific)).orElseThrow());
    }
    @Test void ambiguousRulesAreRejected() {
        assertThrows(IllegalStateException.class, () -> ResolveAssigneeListener.selectDelegation(
                List.of(rule("hr-review", "a"), rule("hr-review", "b"))));
        assertThrows(IllegalStateException.class, () -> ResolveAssigneeListener.selectDelegation(
                List.of(rule("ALL", "a"), rule(null, "b"))));
    }
    @Test void noRuleAndBlankTargetAreHandled() {
        assertTrue(ResolveAssigneeListener.selectDelegation(List.of()).isEmpty());
        assertThrows(IllegalStateException.class, () -> ResolveAssigneeListener.selectDelegation(
                List.of(rule("hr-review", " "))));
    }
}
