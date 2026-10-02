package com.example.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.notification.model.NotificationAccepted;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.NotificationService;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({NotificationController.class, TemplateController.class})
@Import({GlobalExceptionHandler.class, TemplateRenderer.class})
class ApiControllersTest {

    @Autowired MockMvc mvc;
    @MockBean NotificationService notificationService;
    @MockBean DeliveryLogService deliveryLogService;
    @MockBean TemplateService templateService;

    private static final String VALID = """
            {"channels":["EMAIL"],"recipients":{"email":"a@b.com"},"body":"hi"}""";

    @Test
    void acceptedReturns202() throws Exception {
        when(notificationService.submit(eq("acme"), eq("k"), any())).thenReturn(new NotificationAccepted("n1", false));
        mvc.perform(post("/api/v1/notifications").header("X-Tenant-Id", "acme").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("n1"));
    }

    @Test
    void duplicateReturns200() throws Exception {
        when(notificationService.submit(any(), any(), any())).thenReturn(new NotificationAccepted("n1", true));
        mvc.perform(post("/api/v1/notifications").header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void missingTenantHeaderIs400() throws Exception {
        mvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTenantIs400() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("X-Tenant-Id", "bad tenant!")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyChannelsIs400() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("X-Tenant-Id", "acme").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":[],\"recipients\":{\"email\":\"a@b.com\"},\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rateLimitedReturns429WithRetryAfter() throws Exception {
        when(notificationService.submit(any(), any(), any())).thenThrow(new ApiException.RateLimited("slow down", 2500));
        mvc.perform(post("/api/v1/notifications").header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3"));
    }

    @Test
    void templateCreateValidatesAndReturns201() throws Exception {
        mvc.perform(post("/api/v1/templates").header("X-Tenant-Id", "acme").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"channel\":\"EMAIL\",\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
        when(templateService.create(eq("acme"), eq("w"), any(), any(), eq("Hi"))).thenReturn(
                new com.example.notification.template.MessageTemplate("acme", "w", com.example.notification.model.Channel.EMAIL, "s", "Hi"));
        mvc.perform(post("/api/v1/templates").header("X-Tenant-Id", "acme").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"w\",\"channel\":\"EMAIL\",\"subject\":\"s\",\"body\":\"Hi\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("w"));
    }

    @Test
    void templateNotFoundIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.get("acme", id)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Template not found"));
        mvc.perform(get("/api/v1/templates/" + id).header("X-Tenant-Id", "acme")).andExpect(status().isNotFound());
    }

    @Test
    void templateListAndDelete() throws Exception {
        when(templateService.list("acme")).thenReturn(List.of());
        mvc.perform(get("/api/v1/templates").header("X-Tenant-Id", "acme")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/templates/" + UUID.randomUUID()).header("X-Tenant-Id", "acme"))
                .andExpect(status().isNoContent());
    }
}
