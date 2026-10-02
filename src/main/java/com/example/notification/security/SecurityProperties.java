package com.example.notification.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param adminToken    secret guarding /api/v1/admin/** (key management). Empty disables the admin API.
 * @param bootstrapKeys tenant -> pre-provisioned API key, registered (hashed) at startup. For dev/test only.
 */
@ConfigurationProperties("notification.security")
public record SecurityProperties(@DefaultValue("") String adminToken,
                                 @DefaultValue Map<String, String> bootstrapKeys) { }
