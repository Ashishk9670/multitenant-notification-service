package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.notification.security.ApiKey;
import com.example.notification.security.ApiKeyRepository;
import com.example.notification.security.ApiKeyService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApiKeyServiceTest {

    ApiKeyRepository repo = mock(ApiKeyRepository.class);
    ApiKeyService service = new ApiKeyService(repo);

    @Test
    void createStoresOnlyHashAndReturnsRawKeyOnce() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var created = service.create("acme", "ci");
        assertThat(created.key()).startsWith("ntf_").hasSizeGreaterThan(30);
        ArgumentCaptor<ApiKey> cap = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getTenantId()).isEqualTo("acme");
        assertThat(cap.getValue().getKeyPrefix()).isEqualTo(created.key().substring(0, 8));
        // the entity never holds the raw key
        assertThat(cap.getValue().toString()).doesNotContain(created.key());
    }

    @Test
    void keysAreUnique() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.create("a", "n").key()).isNotEqualTo(service.create("a", "n").key());
    }

    @Test
    void authenticateResolvesTenantFromHash() {
        when(repo.findByKeyHashAndRevokedAtIsNull(any())).thenReturn(Optional.of(new ApiKey("acme", "n", "ntf_abcd", "h")));
        assertThat(service.authenticate("ntf_0123456789abcdef")).get().extracting("tenantId").isEqualTo("acme");
    }

    @Test
    void malformedKeysNeverHitTheDatabase() {
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("short")).isEmpty();
        assertThat(service.authenticate("x".repeat(300))).isEmpty();
        verifyNoInteractions(repo);
    }
}
