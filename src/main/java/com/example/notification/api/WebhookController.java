package com.example.notification.api;

import com.example.notification.security.TenantPrincipal;
import com.example.notification.webhook.Webhook;
import com.example.notification.webhook.WebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Delivery-receipt webhook management. The signing secret is returned only on creation and rotation. */
@RestController
@RequestMapping("/api/v1/webhook")
@Validated
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    public record WebhookRequest(@NotBlank @Size(max = 2048) String url) { }

    public record WebhookView(String url, String secret, Instant createdAt, Instant updatedAt) {
        static WebhookView of(Webhook w, String secret) {
            return new WebhookView(w.getUrl(), secret, w.getCreatedAt(), w.getUpdatedAt());
        }
    }

    @GetMapping
    public WebhookView get(@AuthenticationPrincipal TenantPrincipal tenant) {
        return WebhookView.of(service.get(tenant.tenantId()), null);
    }

    /** Creates (201, with secret) or updates the URL (200, no secret). */
    @PutMapping
    public org.springframework.http.ResponseEntity<WebhookView> put(@AuthenticationPrincipal TenantPrincipal tenant,
                                                                    @Valid @RequestBody WebhookRequest r) {
        var existed = service.find(tenant.tenantId()).isPresent();
        var reg = service.put(tenant.tenantId(), r.url());
        return org.springframework.http.ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED)
                .body(WebhookView.of(reg.webhook(), reg.secret()));
    }

    @PostMapping("/rotate-secret")
    public WebhookView rotate(@AuthenticationPrincipal TenantPrincipal tenant) {
        var reg = service.rotateSecret(tenant.tenantId());
        return WebhookView.of(reg.webhook(), reg.secret());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantPrincipal tenant) {
        service.delete(tenant.tenantId());
    }
}
