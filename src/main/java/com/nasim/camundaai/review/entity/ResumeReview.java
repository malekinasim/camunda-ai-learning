package com.nasim.camundaai.review.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "resume_review",
        uniqueConstraints = {@UniqueConstraint(name = "uk_process_instance_key", columnNames = {"processInstanceKey"})})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ResumeReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id")
    private Job job;

    @ManyToOne(optional = false)
    @JoinColumn(name = "resume_id")
    private Resume resume;

    private Long processInstanceKey;

    private Double matchScore;

    private String verdict;

    @Column(columnDefinition = "text")
    private String reason;

    // Saved before completion so retries reuse the same model decision.
    private String agentTaskId;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean agentCompletionConfirmed;

    @CreatedDate
    private LocalDateTime createdDate;

    @LastModifiedDate
    private LocalDateTime lastModifiedDate;

    public ResumeReview(Job job, Resume resume, Long processInstanceKey) {
        this.job = job;
        this.resume = resume;
        this.processInstanceKey = processInstanceKey;
    }
}
