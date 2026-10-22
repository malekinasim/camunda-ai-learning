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
@Table(name = "review_attempt",
        uniqueConstraints = {@UniqueConstraint(name = "uk_reviewer_task_id", columnNames = {"taskId","reviewer"})})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ResumeReviewAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "request_id")
    private ResumeReviewRequest resumeReviewRequest;

    private Double matchScore;

    private String verdict;

    @Column(columnDefinition = "text")
    private String reason;

    // Saved before completion so retries reuse the same model decision.
    private String taskId;

    private String reviewer;

    @CreatedDate
    private LocalDateTime createdDate;

    @LastModifiedDate
    private LocalDateTime lastModifiedDate;

    @Enumerated(EnumType.STRING)
    private AttemptStatus status;

    private LocalDateTime completedAt;

    public ResumeReviewAttempt(String taskId, String reason, String verdict, Double matchScore,
                               String reviewer,
                               ResumeReviewRequest resumeReviewRequest,AttemptStatus status) {
        this.taskId = taskId;
        this.reason = reason;
        this.verdict = verdict;
        this.matchScore = matchScore;
        this.reviewer = reviewer;
        this.resumeReviewRequest = resumeReviewRequest;
        this.status=status;
    }
    public  enum AttemptStatus{
        RESULT_SAVED, WITHDRAW,COMPLETED
    }
}
