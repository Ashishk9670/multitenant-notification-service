package com.example.notification.api;

import com.example.notification.model.NotificationAccepted;
import com.example.notification.model.NotificationRequest;
import com.example.notification.service.Delivery;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.NotificationService;
import com.example.notification.security.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class NotificationController {

    static final String TENANT_REGEX = "^[A-Za-z0-9_-]{1,64}$";

    private final NotificationService service;
    private final DeliveryLogService deliveries;

    public NotificationController(NotificationService service, DeliveryLogService deliveries) {
        this.service = service;
        this.deliveries = deliveries;
    }

    @Operation(summary = "Submit a notification; 202 on accept, 200 when replayed with the same Idempotency-Key")
    @PostMapping("/notifications")
    public ResponseEntity<NotificationAccepted> submit(
            @AuthenticationPrincipal TenantPrincipal tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody NotificationRequest request) {
        NotificationAccepted res = service.submit(tenant.tenantId(), idempotencyKey, request);
        return ResponseEntity.status(res.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(res);
    }

    @Operation(summary = "Delivery status of one notification (per channel)")
    @GetMapping("/notifications/{id}")
    public List<DeliveryView> status(@AuthenticationPrincipal TenantPrincipal tenant, @PathVariable String id) {
        return deliveries.forNotification(tenant.tenantId(), id).stream().map(DeliveryView::of).toList();
    }

    @Operation(summary = "Recent deliveries for the tenant")
    @GetMapping("/deliveries")
    public List<DeliveryView> recent(@AuthenticationPrincipal TenantPrincipal tenant,
                                     @RequestParam(defaultValue = "50") int limit) {
        return deliveries.recent(tenant.tenantId(), limit).stream().map(DeliveryView::of).toList();
    }

    public record DeliveryView(String notificationId, String channel, String status, int attempts,
                               String lastError, java.time.Instant createdAt, java.time.Instant updatedAt) {
        static DeliveryView of(Delivery d) {
            return new DeliveryView(d.getNotificationId(), d.getChannel().name(), d.getStatus().name(),
                    d.getAttempts(), d.getLastError(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }
}
