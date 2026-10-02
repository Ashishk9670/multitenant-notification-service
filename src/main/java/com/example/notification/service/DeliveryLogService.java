package com.example.notification.service;

import com.example.notification.model.DeliveryEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryLogService {

    private final DeliveryRepository repo;

    public DeliveryLogService(DeliveryRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void record(DeliveryEvent e, DeliveryStatus status, String error) {
        String id = e.id() + ":" + e.channel();
        Delivery d = repo.findById(id).orElseGet(() -> new Delivery(id, e.id(), e.tenantId(), e.channel(), e.createdAt()));
        d.setStatus(status);
        d.setAttempts(e.attempt());
        d.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
        d.setUpdatedAt(Instant.now());
        repo.save(d);
    }

    @Transactional(readOnly = true)
    public List<Delivery> forNotification(String tenantId, String notificationId) {
        return repo.findByTenantIdAndNotificationId(tenantId, notificationId);
    }

    @Transactional(readOnly = true)
    public List<Delivery> recent(String tenantId, int limit) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }
}
