package com.tinyclaw.config;

import com.tinyclaw.adapters.web.ApiKeyAuthFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Spring Security 配置。
 *
 * <p>支持 API Key 认证（Header: X-API-Key），分级权限控制（User / Admin）。
 * Actuator 端点允许匿名访问，API 端点需要认证。</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityConfiguration.SecurityProperties.class)
public class SecurityConfiguration {

    private final SecurityProperties securityProperties;

    public SecurityConfiguration(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (!securityProperties.isEnabled()) {
            // 安全模式关闭：允许所有请求
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }

        ApiKeyAuthFilter apiKeyFilter = new ApiKeyAuthFilter(
            securityProperties.getApiKey().getUserKey(),
            securityProperties.getApiKey().getAdminKey()
        );

        return http
            .csrf(AbstractHttpConfigurer::disable)
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/v1/approvals/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
                })
            )
            .build();
    }

    @ConfigurationProperties(prefix = "tiny-claw.security")
    public static class SecurityProperties {
        private boolean enabled = true;
        private ApiKeyProperties apiKey = new ApiKeyProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ApiKeyProperties getApiKey() {
            return apiKey;
        }

        public void setApiKey(ApiKeyProperties apiKey) {
            this.apiKey = apiKey;
        }

        public static class ApiKeyProperties {
            private String userKey = "";
            private String adminKey = "";

            public String getUserKey() {
                return userKey;
            }

            public void setUserKey(String userKey) {
                this.userKey = userKey;
            }

            public String getAdminKey() {
                return adminKey;
            }

            public void setAdminKey(String adminKey) {
                this.adminKey = adminKey;
            }
        }
    }
}
