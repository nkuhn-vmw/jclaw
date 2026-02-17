package com.jclaw.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdentityMappingRepository extends JpaRepository<IdentityMapping, UUID> {

    Optional<IdentityMapping> findByChannelTypeAndChannelUserId(String channelType, String channelUserId);

    List<IdentityMapping> findByJclawPrincipal(String principal);

    List<IdentityMapping> findByApprovedFalse();

    List<IdentityMapping> findByChannelType(String channelType);
}
