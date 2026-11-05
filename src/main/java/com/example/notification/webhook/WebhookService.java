package com.example.notification.webhook;

import com.example.notification.api.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    /** secret is non-null only when it was just generated (creation or rotation). */
    public record Registered(Webhook webhook, String secret) { }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookRepository repo;
    private final WebhookUrlValidator validator;

    public WebhookService(WebhookRepository repo, WebhookUrlValidator validator) {
        this.repo = repo;
        this.validator = validator;
    }

    @Transactional
    public Registered put(String tenantId, String url) {
        String normalized;
        try {
            normalized = validator.validate(url).toString();
        } catch (WebhookUrlValidator.InvalidWebhookUrlException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        Instant now = Instant.now();
        Optional<Webhook> existing = repo.findById(tenantId);
        if (existing.isPresent()) {
            Webhook w = existing.get();
            w.setUrl(normalized, now);
            return new Registered(repo.save(w), null);
        }
        String secret = newSecret();
        return new Registered(repo.save(new Webhook(tenantId, normalized, secret, now)), secret);
    }

    @Transactional(readOnly = true)
    public Webhook get(String tenantId) {
        return repo.findById(tenantId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No webhook registered"));
    }

    @Transactional(readOnly = true)
    public Optional<Webhook> find(String tenantId) {
        return repo.findById(tenantId);
    }

    @Transactional
    public Registered rotateSecret(String tenantId) {
        Webhook w = repo.findById(tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No webhook registered"));
        String secret = newSecret();
        w.setSecret(secret, Instant.now());
        return new Registered(repo.save(w), secret);
    }

    @Transactional
    public void delete(String tenantId) {
        if (!repo.existsById(tenantId)) throw new ApiException(HttpStatus.NOT_FOUND, "No webhook registered");
        repo.deleteById(tenantId);
    }

    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + HexFormat.of().formatHex(b);
    }
}
