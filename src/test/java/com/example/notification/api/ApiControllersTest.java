package com.example.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.notification.model.NotificationAccepted;
import com.example.notification.security.ApiKeyService;
import com.example.notification.security.SecurityConfig;
import com.example.notification.security.TenantPrincipal;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.NotificationService;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({NotificationController.class, TemplateController.class, AdminController.class})
@Import({GlobalExceptionHandler.class, TemplateRenderer.class, SecurityConfig.class})
@TestPropertySource(properties = "notification.security.admin-token=admin-secret")
class ApiControllersTest {

    @Autowired MockMvc mvc;
    @MockBean NotificationService notificationService;
    @MockBean DeliveryLogService deliveryLogService;
    @MockBean TemplateService templateService;
    @MockBean ApiKeyService apiKeyService;

    private static final String KEY = "ntf_test_key_for_acme_0123456789";

    @BeforeEach
    void auth() {
        when(apiKeyService.authenticate(KEY)).thenReturn(Optional.of(new TenantPrincipal("acme")));
    }

    private static final String VALID = """
            {"channels":["EMAIL"],"recipients":{"email":"a@b.com"},"body":"hi"}""";

    @Test
    void acceptedReturns202() throws Exception {
        when(notificationService.submit(eq("acme"), eq("k"), any())).thenReturn(new NotificationAccepted("n1", false));
        mvc.perform(post("/api/v1/notifications").header("X-API-Key", KEY).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("n1"));
    }

    @Test
    void duplicateReturns200() throws Exception {
        when(notificationService.submit(any(), any(), any())).thenReturn(new NotificationAccepted("n1", true));
        mvc.perform(post("/api/v1/notifications").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void missingApiKeyIs401() throws Exception {
        mvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownApiKeyIs401() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("X-API-Key", "ntf_unknown_unknown_unknown")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantHeaderAloneIsNotTrusted() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantComesFromKeyNotFromSpoofedHeader() throws Exception {
        when(notificationService.submit(eq("acme"), any(), any())).thenReturn(new NotificationAccepted("n1", false));
        mvc.perform(post("/api/v1/notifications").header("X-API-Key", KEY).header("X-Tenant-Id", "victim")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted());
        org.mockito.Mockito.verify(notificationService).submit(eq("acme"), any(), any());
    }

    @Test
    void bearerTokenIsAccepted() throws Exception {
        when(notificationService.submit(eq("acme"), any(), any())).thenReturn(new NotificationAccepted("n1", false));
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted());
    }

    @Test
    void adminApiRequiresAdminToken() throws Exception {
        mvc.perform(post("/api/v1/admin/api-keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\",\"name\":\"k\"}")).andExpect(status().isUnauthorized());
        // a tenant key must not open the admin API
        mvc.perform(post("/api/v1/admin/api-keys").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\",\"name\":\"k\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/api-keys").header("X-Admin-Token", "wrong").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"acme\",\"name\":\"k\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanIssueKey() throws Exception {
        when(apiKeyService.create("acme", "k")).thenReturn(
                new ApiKeyService.CreatedKey(UUID.randomUUID(), "acme", "k", "ntf_secret"));
        mvc.perform(post("/api/v1/admin/api-keys").header("X-Admin-Token", "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"acme\",\"name\":\"k\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.apiKey").value("ntf_secret"));
        mvc.perform(post("/api/v1/admin/api-keys").header("X-Admin-Token", "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"bad tenant!\",\"name\":\"k\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyChannelsIs400() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":[],\"recipients\":{\"email\":\"a@b.com\"},\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rateLimitedReturns429WithRetryAfter() throws Exception {
        when(notificationService.submit(any(), any(), any())).thenThrow(new ApiException.RateLimited("slow down", 2500));
        mvc.perform(post("/api/v1/notifications").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3"));
    }

    @Test
    void templateCreateValidatesAndReturns201() throws Exception {
        mvc.perform(post("/api/v1/templates").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"channel\":\"EMAIL\",\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
        when(templateService.create(eq("acme"), eq("w"), any(), any(), eq("Hi"))).thenReturn(
                new com.example.notification.template.MessageTemplate("acme", "w", com.example.notification.model.Channel.EMAIL, "s", "Hi"));
        mvc.perform(post("/api/v1/templates").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"w\",\"channel\":\"EMAIL\",\"subject\":\"s\",\"body\":\"Hi\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("w"));
    }

    @Test
    void templateNotFoundIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.get("acme", id)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Template not found"));
        mvc.perform(get("/api/v1/templates/" + id).header("X-API-Key", KEY)).andExpect(status().isNotFound());
    }

    @Test
    void templateListAndDelete() throws Exception {
        when(templateService.list("acme")).thenReturn(List.of());
        mvc.perform(get("/api/v1/templates").header("X-API-Key", KEY)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/templates/" + UUID.randomUUID()).header("X-API-Key", KEY))
                .andExpect(status().isNoContent());
    }
}
