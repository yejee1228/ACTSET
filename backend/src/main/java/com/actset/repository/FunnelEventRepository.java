package com.actset.repository;

import com.actset.domain.FunnelEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FunnelEventRepository extends JpaRepository<FunnelEvent, Long> {
}
