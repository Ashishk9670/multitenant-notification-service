package com.example.notification.api;

import com.example.notification.model.Channel;
import com.example.notification.security.TenantPrincipal;
import com.example.notification.template.MessageTemplate;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/templates")
@Validated
public class TemplateController {

    private final TemplateService service;
    private final TemplateRenderer renderer;

    public TemplateController(TemplateService service, TemplateRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    public record TemplateRequest(@NotBlank @Size(max = 128) String name, @NotNull Channel channel,
                                  @Size(max = 512) String subject, @NotBlank @Size(max = 10_000) String body) { }

    public record TemplateView(UUID id, String name, Channel channel, String subject, String body,
                               Instant createdAt, Instant updatedAt) {
        static TemplateView of(MessageTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getChannel(), t.getSubject(), t.getBody(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record PreviewRequest(@Size(max = 50) Map<@Size(max = 64) String, @Size(max = 2000) String> variables) { }

    public record PreviewResponse(String subject, String body) { }

    @GetMapping
    public List<TemplateView> list(@AuthenticationPrincipal TenantPrincipal tenant) {
        return service.list(tenant.tenantId()).stream().map(TemplateView::of).toList();
    }

    @GetMapping("/{id}")
    public TemplateView get(@AuthenticationPrincipal TenantPrincipal tenant,
                            @PathVariable UUID id) {
        return TemplateView.of(service.get(tenant.tenantId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView create(@AuthenticationPrincipal TenantPrincipal tenant,
                               @Valid @RequestBody TemplateRequest r) {
        return TemplateView.of(service.create(tenant.tenantId(), r.name(), r.channel(), r.subject(), r.body()));
    }

    @PutMapping("/{id}")
    public TemplateView update(@AuthenticationPrincipal TenantPrincipal tenant,
                               @PathVariable UUID id, @Valid @RequestBody TemplateRequest r) {
        return TemplateView.of(service.update(tenant.tenantId(), id, r.name(), r.channel(), r.subject(), r.body()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantPrincipal tenant,
                       @PathVariable UUID id) {
        service.delete(tenant.tenantId(), id);
    }

    @PostMapping("/{id}/preview")
    public PreviewResponse preview(@AuthenticationPrincipal TenantPrincipal tenant,
                                   @PathVariable UUID id, @Valid @RequestBody PreviewRequest r) {
        MessageTemplate t = service.get(tenant.tenantId(), id);
        try {
            return new PreviewResponse(renderer.render(t.getSubject(), r.variables()), renderer.render(t.getBody(), r.variables()));
        } catch (TemplateRenderer.MissingVariablesException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }
}
