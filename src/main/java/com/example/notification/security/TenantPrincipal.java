package com.example.notification.security;

/** The authenticated tenant, derived from a verified API key (never from a client-supplied header). */
public record TenantPrincipal(String tenantId) { }
