package com.example.notification.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;

public record NotificationRequest(
        @NotEmpty @Size(max = 3) Set<Channel> channels,
        Priority priority,
        @NotNull @Valid Recipients recipients,
        @Size(max = 128) String templateName,
        @Size(max = 50) Map<@Size(max = 64) String, @Size(max = 2000) String> variables,
        @Size(max = 512) String subject,
        @Size(max = 10_000) String body) { }
