package com.example.notification.service;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, String> {
    List<Delivery> findByTenantIdAndNotificationId(String tenantId, String notificationId);
    List<Delivery> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
}
