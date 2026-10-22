package com.nasim.camundaai.review.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "resume_review_request",
        uniqueConstraints = {@UniqueConstraint(name = "uk_process_instance_key", columnNames = {"processInstanceKey"})})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ResumeReviewRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id")
    private Job job;

    @ManyToOne(optional = false)
    @JoinColumn(name = "resume_id")
    private Resume resume;


    @OneToMany(targetEntity = ResumeReviewAttempt.class,
            fetch = FetchType.LAZY , orphanRemoval = true,
            mappedBy = "resumeReviewRequest")
    private Set<ResumeReviewAttempt> resumeReviewAttempts = new HashSet<>();

    private Long processInstanceKey;

    @CreatedDate
    private LocalDateTime createdDate;

    @Enumerated(EnumType.STRING)
    private RequestStatus status;

    @ManyToOne
    @JoinColumn(name = "final_review_id")
    private ResumeReviewAttempt finalReviewAttempt;

    @LastModifiedDate
    private LocalDateTime lastModifiedDate;

    public ResumeReviewRequest(Job job, Resume resume, Long processInstanceKey,RequestStatus status) {
        this.job = job;
        this.resume = resume;
        this.processInstanceKey = processInstanceKey;
        this.status=status;
    }
    public  enum RequestStatus{
        OPEN,COMPLETED
    }
}
