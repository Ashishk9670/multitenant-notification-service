package com.example.notification.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates /api requests. Tenant routes need {@code X-API-Key} (or {@code Authorization: Bearer}) which is
 * resolved to a tenant server-side; admin routes need {@code X-Admin-Token}. Anything else stays anonymous
 * and is rejected by the authorization rules.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String ADMIN_HEADER = "X-Admin-Token";

    private final ApiKeyService keys;
    private final byte[] adminToken;

    public ApiKeyAuthFilter(ApiKeyService keys, String adminToken) {
        this.keys = keys;
        this.adminToken = adminToken == null ? new byte[0] : adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/api/v1/admin/")) {
            String presented = req.getHeader(ADMIN_HEADER);
            if (adminToken.length > 0 && presented != null
                    && MessageDigest.isEqual(adminToken, presented.getBytes(StandardCharsets.UTF_8))) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            }
        } else {
            Optional<TenantPrincipal> p = keys.authenticate(extractKey(req));
            p.ifPresent(tp -> SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    tp, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")))));
        }
        chain.doFilter(req, res);
    }

    private static String extractKey(HttpServletRequest req) {
        String k = req.getHeader(API_KEY_HEADER);
        if (k != null) return k.trim();
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        return null;
    }
}
