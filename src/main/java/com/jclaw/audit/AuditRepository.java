package com.jclaw.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByPrincipalOrderByTimestampDesc(String principal, Pageable pageable);

    Page<AuditEvent> findByEventTypeOrderByTimestampDesc(String eventType, Pageable pageable);

    Page<AuditEvent> findByAgentIdOrderByTimestampDesc(String agentId, Pageable pageable);

    List<AuditEvent> findByTimestampBefore(Instant cutoff);

    long deleteByTimestampBefore(Instant cutoff);
}
