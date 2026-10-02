package com.example.notification.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
    boolean existsByKeyHash(String keyHash);
    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
