package com.nasim.camundaai.review.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "request_id")
    private ResumeReviewRequest resumeReviewRequest;

    private Double matchScore;

    private String verdict;

    @Column(columnDefinition = "text")
    private String reason;

    private String skillVersion;

    private String model;

    private String taskId;

    private String reviewer;

    @CreatedDate
    private LocalDateTime createdDate;

    @LastModifiedDate
    private LocalDateTime lastModifiedDate;

    @Enumerated(EnumType.STRING)
    private AttemptStatus status;

    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    private ProposalReview proposalReview;                                        // added (#31): null on the agent's own attempts

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
        RESULT_SAVED, WITHDRAWN,COMPLETED
    }

    /** What the person did with the agent's proposal (#31). */
    public enum ProposalReview { ACCEPTED, SCORE_CHANGED, VERDICT_CHANGED, NO_PROPOSAL }
}
