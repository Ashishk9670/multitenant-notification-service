package com.example.notification.template;

import com.example.notification.model.Channel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<MessageTemplate, UUID> {
    List<MessageTemplate> findByTenantIdOrderByNameAsc(String tenantId);
    Optional<MessageTemplate> findByIdAndTenantId(UUID id, String tenantId);
    Optional<MessageTemplate> findByTenantIdAndNameAndChannel(String tenantId, String name, Channel channel);
    boolean existsByTenantIdAndNameAndChannel(String tenantId, String name, Channel channel);
}
