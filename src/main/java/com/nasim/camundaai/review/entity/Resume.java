package com.nasim.camundaai.review.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "resume")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String candidateName;

    @Lob
    private String resumeText;

    @CreatedDate
    private LocalDateTime createdDate;

    public Resume(String candidateName, String resumeText) {
        this.candidateName = candidateName;
        this.resumeText = resumeText;
    }
}