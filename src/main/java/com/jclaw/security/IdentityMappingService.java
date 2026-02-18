package com.jclaw.security;

import com.jclaw.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IdentityMappingService {

    private static final Logger log = LoggerFactory.getLogger(IdentityMappingService.class);

    private final IdentityMappingRepository repository;
    private final AuditService auditService;

    public IdentityMappingService(IdentityMappingRepository repository,
                                  AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public Mono<String> resolvePrincipal(String channelType, String channelUserId) {
        return Mono.fromCallable(() ->
            repository.findByChannelTypeAndChannelUserId(channelType, channelUserId)
                .filter(m -> m.isApproved() && m.getJclawPrincipal() != null
                        && !m.getJclawPrincipal().isBlank())
                .map(mapping -> {
                    mapping.setLastSeenAt(Instant.now());
                    repository.save(mapping);
                    return mapping.getJclawPrincipal();
                })
                .orElseThrow(() -> new UnmappedIdentityException(
                        "No approved mapping for " + channelType + ":" + channelUserId))
        );
    }

    @Transactional
    public IdentityMapping createMapping(String channelType, String channelUserId,
                                        String jclawPrincipal, String displayName) {
        IdentityMapping mapping = new IdentityMapping(channelType, channelUserId, jclawPrincipal);
        mapping.setDisplayName(displayName);
        IdentityMapping saved = repository.save(mapping);
        log.info("Identity mapping created: {}:{} -> {}", channelType, channelUserId, jclawPrincipal);
        return saved;
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public IdentityMapping approveMapping(UUID mappingId, String approvedBy, String jclawPrincipal) {
        if (jclawPrincipal == null || jclawPrincipal.isBlank()) {
            throw new IllegalArgumentException("jclawPrincipal must not be null or blank");
        }
        IdentityMapping mapping = repository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        mapping.setJclawPrincipal(jclawPrincipal);
        mapping.setApproved(true);
        mapping.setApprovedBy(approvedBy);
        IdentityMapping saved = repository.save(mapping);
        auditService.logConfigChange(approvedBy, null, "IDENTITY_MAPPING_APPROVED",
                "{\"mappingId\":\"" + mappingId + "\",\"principal\":\"" + mapping.getJclawPrincipal() + "\"}");
        log.info("Identity mapping approved: {} by {}", mappingId, approvedBy);
        return saved;
    }

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public List<IdentityMapping> getPendingMappings() {
        return repository.findByApprovedFalse();
    }

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public List<IdentityMapping> getMappingsForPrincipal(String principal) {
        return repository.findByJclawPrincipal(principal);
    }

    public static class UnmappedIdentityException extends RuntimeException {
        public UnmappedIdentityException(String message) { super(message); }
    }
}
