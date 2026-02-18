package com.jclaw.session;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SessionMessageRepository extends JpaRepository<SessionMessage, UUID> {

    List<SessionMessage> findBySessionIdAndCompactedFalseOrderByCreatedAtAsc(UUID sessionId);

    List<SessionMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(m.tokenCount), 0) FROM SessionMessage m WHERE m.sessionId = ?1 AND m.compacted = false")
    int sumTokensBySessionId(UUID sessionId);

    long deleteBySessionIdAndCreatedAtBefore(UUID sessionId, Instant cutoff);

    long deleteBySessionId(UUID sessionId);

    int countBySessionId(UUID sessionId);
}
