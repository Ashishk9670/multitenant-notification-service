package com.example.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notification.api.ApiException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookServiceTest {

    WebhookRepository repo = mock(WebhookRepository.class);
    WebhookService service = new WebhookService(repo,
            new WebhookUrlValidator(h -> new InetAddress[] {InetAddress.getByName(h.equals("evil.example") ? "10.0.0.1" : "93.184.216.34")}));

    @BeforeEach
    void setUp() {
        when(repo.save(any(Webhook.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createReturnsGeneratedSecret() {
        when(repo.findById("acme")).thenReturn(Optional.empty());
        var r = service.put("acme", "https://hooks.example.com/r");
        assertThat(r.secret()).startsWith("whsec_").hasSize(6 + 64);
        assertThat(r.webhook().getSecret()).isEqualTo(r.secret());
    }

    @Test
    void updateKeepsSecretHidden() {
        Webhook w = new Webhook("acme", "https://old.example.com/", "whsec_keep", Instant.now());
        when(repo.findById("acme")).thenReturn(Optional.of(w));
        var r = service.put("acme", "https://new.example.com/");
        assertThat(r.secret()).isNull();
        assertThat(r.webhook().getUrl()).isEqualTo("https://new.example.com/");
        assertThat(r.webhook().getSecret()).isEqualTo("whsec_keep");
    }

    @Test
    void unsafeUrlIsRejectedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.put("acme", "https://evil.example/"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(repo, never()).save(any());
    }

    @Test
    void rotateChangesSecretAndMissingIs404() {
        Webhook w = new Webhook("acme", "https://h.example.com/", "whsec_old", Instant.now());
        when(repo.findById("acme")).thenReturn(Optional.of(w));
        var r = service.rotateSecret("acme");
        assertThat(r.secret()).isNotEqualTo("whsec_old");
        when(repo.findById("none")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotateSecret("none"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteMissingIs404() {
        when(repo.existsById("none")).thenReturn(false);
        assertThatThrownBy(() -> service.delete("none")).isInstanceOf(ApiException.class);
        when(repo.existsById("acme")).thenReturn(true);
        service.delete("acme");
        verify(repo).deleteById("acme");
    }
}
