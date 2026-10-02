package com.example.notification.security;

import com.example.notification.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant API keys. Only the SHA-256 of a key is stored: keys are 256-bit random secrets, so a fast hash is
 * sufficient (no brute-force surface) and lets us look a key up by its hash in constant time.
 */
@Service
public class ApiKeyService {

    public static final String PREFIX = "ntf_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repo;

    public ApiKeyService(ApiKeyRepository repo) {
        this.repo = repo;
    }

    public record CreatedKey(UUID id, String tenantId, String name, String key) { }

    @Transactional
    public CreatedKey create(String tenantId, String name) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ApiKey saved = repo.save(new ApiKey(tenantId, name, raw.substring(0, 8), hash(raw)));
        return new CreatedKey(saved.getId(), tenantId, name, raw);
    }

    /** Registers a pre-provisioned key (bootstrap / dev); idempotent. */
    @Transactional
    public void register(String tenantId, String name, String rawKey) {
        String h = hash(rawKey);
        if (!repo.existsByKeyHash(h)) {
            repo.save(new ApiKey(tenantId, name, rawKey.substring(0, Math.min(8, rawKey.length())), h));
        }
    }

    @Transactional(readOnly = true)
    public Optional<TenantPrincipal> authenticate(String rawKey) {
        if (rawKey == null || rawKey.length() < 16 || rawKey.length() > 256) return Optional.empty();
        return repo.findByKeyHashAndRevokedAtIsNull(hash(rawKey)).map(k -> new TenantPrincipal(k.getTenantId()));
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String tenantId) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public void revoke(UUID id) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API key not found"));
        k.revoke();
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
