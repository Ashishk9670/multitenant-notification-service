package com.example.notification.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;

public record NotificationRequest(
        @NotEmpty Set<Channel> channels,
        Priority priority,
        @NotNull @Valid Recipients recipients,
        String templateName,
        Map<String, String> variables,
        String subject,
        String body) { }
