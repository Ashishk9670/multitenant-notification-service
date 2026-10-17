package com.example.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.notification.security.ApiKeyService;
import com.example.notification.security.SecurityConfig;
import com.example.notification.security.TenantPrincipal;
import com.example.notification.webhook.Webhook;
import com.example.notification.webhook.WebhookService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebhookController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "notification.security.admin-token=admin-secret")
class WebhookControllerTest {

    private static final String KEY = "ntf_test_key_for_acme_0123456789";

    @Autowired MockMvc mvc;
    @MockBean WebhookService service;
    @MockBean ApiKeyService apiKeyService;

    @BeforeEach
    void auth() {
        when(apiKeyService.authenticate(KEY)).thenReturn(Optional.of(new TenantPrincipal("acme")));
    }

    private Webhook hook() {
        return new Webhook("acme", "https://hooks.example.com/r", "whsec_x", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/webhook")).andExpect(status().isUnauthorized());
    }

    @Test
    void firstPutCreatesAndRevealsSecretOnce() throws Exception {
        when(service.find("acme")).thenReturn(Optional.empty());
        when(service.put("acme", "https://hooks.example.com/r"))
                .thenReturn(new WebhookService.Registered(hook(), "whsec_generated"));
        mvc.perform(put("/api/v1/webhook").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/r\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.secret").value("whsec_generated"));
    }

    @Test
    void updateDoesNotRevealSecret() throws Exception {
        when(service.find("acme")).thenReturn(Optional.of(hook()));
        when(service.put(eq("acme"), any())).thenReturn(new WebhookService.Registered(hook(), null));
        mvc.perform(put("/api/v1/webhook").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/r\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void unsafeUrlIsUnprocessable() throws Exception {
        when(service.find("acme")).thenReturn(Optional.empty());
        when(service.put(eq("acme"), any()))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Webhook URL must use https"));
        mvc.perform(put("/api/v1/webhook").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void blankUrlIsRejectedByValidation() throws Exception {
        mvc.perform(put("/api/v1/webhook").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void getNeverReturnsSecret() throws Exception {
        when(service.get("acme")).thenReturn(hook());
        mvc.perform(get("/api/v1/webhook").header("X-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://hooks.example.com/r"))
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void rotateRevealsNewSecretAndDeleteReturns204() throws Exception {
        when(service.rotateSecret("acme")).thenReturn(new WebhookService.Registered(hook(), "whsec_new"));
        mvc.perform(post("/api/v1/webhook/rotate-secret").header("X-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").value("whsec_new"));
        mvc.perform(delete("/api/v1/webhook").header("X-API-Key", KEY)).andExpect(status().isNoContent());
        verify(service).delete("acme");
    }
}
