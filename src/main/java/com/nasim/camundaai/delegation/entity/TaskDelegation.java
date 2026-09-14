package com.nasim.camundaai.delegation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class TaskDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String delegatorRole;

    private String taskType;

    private String delegateTo;
    private LocalDate startDate;
    private LocalDate endDate;

    @Version
    private int version;

    @CreatedBy
    private String createdBy;
    @CreatedDate
    private LocalDateTime createdDate;
    @LastModifiedBy
    private String lastModifiedBy;
    @LastModifiedDate
    private LocalDateTime lastModifiedDate;

    public TaskDelegation(String delegatorRole, String taskType, String delegateTo, LocalDate startDate, LocalDate endDate) {
        this.delegatorRole = delegatorRole;
        this.taskType = taskType;
        this.delegateTo = delegateTo;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}