package com.nasim.camundaai.review.repository;

import com.nasim.camundaai.review.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
}