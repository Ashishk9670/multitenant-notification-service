package com.example.notification.api;

import com.example.notification.model.Channel;
import com.example.notification.template.MessageTemplate;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
                                  @Size(max = 512) String subject, @NotBlank String body) { }

    public record TemplateView(UUID id, String name, Channel channel, String subject, String body,
                               Instant createdAt, Instant updatedAt) {
        static TemplateView of(MessageTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getChannel(), t.getSubject(), t.getBody(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record PreviewRequest(Map<String, String> variables) { }

    public record PreviewResponse(String subject, String body) { }

    @GetMapping
    public List<TemplateView> list(@RequestHeader("X-Tenant-Id") @Pattern(regexp = NotificationController.TENANT_REGEX) String tenant) {
        return service.list(tenant).stream().map(TemplateView::of).toList();
    }

    @GetMapping("/{id}")
    public TemplateView get(@RequestHeader("X-Tenant-Id") @Pattern(regexp = NotificationController.TENANT_REGEX) String tenant,
                            @PathVariable UUID id) {
        return TemplateView.of(service.get(tenant, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView create(@RequestHeader("X-Tenant-Id") @Pattern(regexp = NotificationController.TENANT_REGEX) String tenant,
                               @Valid @RequestBody TemplateRequest r) {
        return TemplateView.of(service.create(tenant, r.name(), r.channel(), r.subject(), r.body()));
    }

    @PutMapping("/{id}")
    public TemplateView update(@RequestHeader("X-Tenant-Id") @Pattern(regexp = NotificationController.TENANT_REGEX) String tenant,
                               @PathVariable UUID id, @Valid @RequestBody TemplateRequest r) {
        return TemplateView.of(service.update(tenant, id, r.name(), r.channel(), r.subject(), r.body()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-Tenant-Id") @Pattern(regexp = NotificationController.TENANT_REGEX) String tenant,
                       @PathVariable UUID id) {
        service.delete(tenant, id);
    }

    @PostMapping("/{id}/preview")
    public PreviewResponse preview(@RequestHeader("X-Tenant-Id") @Pattern(regexp = NotificationController.TENANT_REGEX) String tenant,
                                   @PathVariable UUID id, @RequestBody PreviewRequest r) {
        MessageTemplate t = service.get(tenant, id);
        try {
            return new PreviewResponse(renderer.render(t.getSubject(), r.variables()), renderer.render(t.getBody(), r.variables()));
        } catch (TemplateRenderer.MissingVariablesException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }
}
