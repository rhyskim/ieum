package com.ieum.backend.domain.question.repository;

import com.ieum.backend.domain.question.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    long countByActiveTrue();
}
