package com.nasim.camundaai.review.repository;

import com.nasim.camundaai.review.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {
}