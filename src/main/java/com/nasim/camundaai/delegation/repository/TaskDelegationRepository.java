package com.nasim.camundaai.delegation.repository;

import com.nasim.camundaai.delegation.entity.TaskDelegation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TaskDelegationRepository extends JpaRepository<TaskDelegation, Long> {

    @Query("""
            select d from TaskDelegation d
            where d.delegatorRole = :delegatorRole
              and (d.taskType is null or d.taskType = 'ALL' or d.taskType = :taskType)
              and d.startDate <= :today
              and (d.endDate is null or d.endDate >= :today)
            """)
    List<TaskDelegation> findActiveDelegations(
            @Param("delegatorRole") String delegatorRole,
            @Param("taskType") String taskType,
            @Param("today") LocalDate today);

    List<TaskDelegation> findAllByOrderByStartDateDesc();
}
