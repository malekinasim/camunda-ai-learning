package com.nasim.camundaai.delegation.controller;

import com.nasim.camundaai.delegation.entity.TaskDelegation;
import com.nasim.camundaai.delegation.repository.TaskDelegationRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/delegations")
public class DelegationController {

    private final TaskDelegationRepository repository;

    public DelegationController(TaskDelegationRepository repository) {
        this.repository = repository;
    }

    public record CreateDelegationRequest(
            String delegatorRole,
            String taskType,
            String delegateTo,
            LocalDate startDate,
            LocalDate endDate) {
    }

    @PostMapping
    public TaskDelegation createDelegation(@RequestBody CreateDelegationRequest request) {
        return repository.save(new TaskDelegation(
                request.delegatorRole(),
                request.taskType(),
                request.delegateTo(),
                request.startDate(),
                request.endDate()
        ));
    }

    @GetMapping
    public List<TaskDelegation> listDelegations() {
        return repository.findAllByOrderByStartDateDesc();
    }
}