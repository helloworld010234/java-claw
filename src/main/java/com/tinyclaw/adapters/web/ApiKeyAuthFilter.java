package com.tinyclaw.adapters.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API Key 认证过滤器。
 *
 * <p>从请求头 {@code X-API-Key} 读取 Key，与配置的 User Key / Admin Key 比对。
 * Admin Key 匹配时授予 {@code ROLE_ADMIN} 权限。</p>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String userApiKey;
    private final String adminApiKey;

    public ApiKeyAuthFilter(String userApiKey, String adminApiKey) {
        this.userApiKey = userApiKey != null ? userApiKey : "";
        this.adminApiKey = adminApiKey != null ? adminApiKey : "";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");

        if (apiKey != null && !apiKey.isBlank()) {
            if (apiKey.equals(adminApiKey) && !adminApiKey.isBlank()) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (apiKey.equals(userApiKey) && !userApiKey.isBlank()) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "user", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}
