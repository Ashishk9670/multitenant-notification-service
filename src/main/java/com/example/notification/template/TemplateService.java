package com.example.notification.template;

import com.example.notification.api.ApiException;
import com.example.notification.model.Channel;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {

    private final TemplateRepository repo;

    public TemplateService(TemplateRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<MessageTemplate> list(String tenantId) {
        return repo.findByTenantIdOrderByNameAsc(tenantId);
    }

    @Transactional(readOnly = true)
    public MessageTemplate get(String tenantId, UUID id) {
        return repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Template not found"));
    }

    @Transactional(readOnly = true)
    public MessageTemplate getByName(String tenantId, String name, Channel channel) {
        return repo.findByTenantIdAndNameAndChannel(tenantId, name, channel)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Template '" + name + "' not found for channel " + channel));
    }

    @Transactional
    public MessageTemplate create(String tenantId, String name, Channel channel, String subject, String body) {
        if (repo.existsByTenantIdAndNameAndChannel(tenantId, name, channel)) {
            throw new ApiException(HttpStatus.CONFLICT, "Template already exists for name/channel");
        }
        return repo.save(new MessageTemplate(tenantId, name, channel, subject, body));
    }

    @Transactional
    public MessageTemplate update(String tenantId, UUID id, String name, Channel channel, String subject, String body) {
        MessageTemplate t = get(tenantId, id);
        repo.findByTenantIdAndNameAndChannel(tenantId, name, channel)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(o -> { throw new ApiException(HttpStatus.CONFLICT, "Template already exists for name/channel"); });
        t.update(name, channel, subject, body);
        return t;
    }

    @Transactional
    public void delete(String tenantId, UUID id) {
        repo.delete(get(tenantId, id));
    }
}
