package com.jclaw.session;

import com.jclaw.audit.AuditRepository;
import com.jclaw.config.JclawProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class SessionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionPurgeScheduler.class);

    private final SessionRepository sessionRepository;
    private final SessionManager sessionManager;
    private final AuditRepository auditRepository;
    private final JclawProperties properties;

    public SessionPurgeScheduler(SessionRepository sessionRepository,
                                SessionManager sessionManager,
                                AuditRepository auditRepository,
                                JclawProperties properties) {
        this.sessionRepository = sessionRepository;
        this.sessionManager = sessionManager;
        this.auditRepository = auditRepository;
        this.properties = properties;
    }

    // Run daily at 3 AM
    @Scheduled(cron = "${jclaw.session.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpiredSessions() {
        int retentionDays = properties.getSecurity().getDataRetention().getSessionTranscriptsDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        List<Session> expiredSessions = sessionRepository
                .findByStatusAndLastActiveAtBefore(SessionStatus.ARCHIVED, cutoff);

        for (Session session : expiredSessions) {
            session.setStatus(SessionStatus.PURGED);
            sessionRepository.save(session);
        }

        log.info("Purged {} expired sessions (cutoff: {} days)", expiredSessions.size(), retentionDays);
    }

    @Scheduled(cron = "${jclaw.session.archive-idle-cron:0 0 * * * *}")
    @Transactional
    public void archiveIdleSessions() {
        int idleMinutes = properties.getSession().getIdleTimeoutMinutes();
        Instant cutoff = Instant.now().minus(idleMinutes, ChronoUnit.MINUTES);

        List<Session> idleSessions = sessionRepository
                .findByStatusAndLastActiveAtBefore(SessionStatus.ACTIVE, cutoff);

        for (Session session : idleSessions) {
            sessionManager.archiveSession(session.getId());
        }

        if (!idleSessions.isEmpty()) {
            log.info("Archived {} idle sessions (idle > {} min)", idleSessions.size(), idleMinutes);
        }
    }

    @Scheduled(cron = "${jclaw.audit.purge-cron:0 0 4 * * *}")
    @Transactional
    public void purgeOldAuditEvents() {
        int retentionDays = properties.getSecurity().getDataRetention().getAuditLogDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        long deleted = auditRepository.deleteByTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} audit events older than {} days", deleted, retentionDays);
        }
    }
}
