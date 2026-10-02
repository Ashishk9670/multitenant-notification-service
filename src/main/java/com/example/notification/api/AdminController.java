package com.example.notification.api;

import com.example.notification.security.ApiKey;
import com.example.notification.security.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** API key management; protected by the operator-held admin token (X-Admin-Token). */
@RestController
@Validated
@RequestMapping("/api/v1/admin/api-keys")
public class AdminController {

    private final ApiKeyService keys;

    public AdminController(ApiKeyService keys) {
        this.keys = keys;
    }

    public record CreateKeyRequest(@NotBlank @Pattern(regexp = NotificationController.TENANT_REGEX) String tenantId,
                                   @NotBlank @Size(max = 128) String name) { }

    public record KeyView(UUID id, String tenantId, String name, String prefix, Instant createdAt, Instant revokedAt) {
        static KeyView of(ApiKey k) {
            return new KeyView(k.getId(), k.getTenantId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getRevokedAt());
        }
    }

    public record CreatedKeyView(UUID id, String tenantId, String name, String apiKey) { }

    @Operation(summary = "Issue a new API key for a tenant. The key is returned once and only its hash is stored.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedKeyView create(@Valid @RequestBody CreateKeyRequest r) {
        ApiKeyService.CreatedKey c = keys.create(r.tenantId(), r.name());
        return new CreatedKeyView(c.id(), c.tenantId(), c.name(), c.key());
    }

    @GetMapping
    public List<KeyView> list(@RequestParam @Pattern(regexp = NotificationController.TENANT_REGEX) String tenantId) {
        return keys.list(tenantId).stream().map(KeyView::of).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        keys.revoke(id);
    }
}
