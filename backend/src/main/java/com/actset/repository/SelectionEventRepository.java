package com.actset.repository;

import com.actset.domain.SelectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelectionEventRepository extends JpaRepository<SelectionEvent, Long> {
}
