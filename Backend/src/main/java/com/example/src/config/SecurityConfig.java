package com.example.src.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;


/**
 * Spring Security configuration (Phase 2 + Phase 6).
 *
 * Auth model:
 *   - Public: GET /api/patterns, /api/health, /api/stats (read-only)
 *   - Authenticated: POST /api/match — requires HTTP Basic Auth (operators must log in)
 *   - Protected (ROLE_ADMIN): /api/admin/** — requires HTTP Basic Auth
 *   - CSRF is disabled (stateless REST API over LAN)
 *   - CORS allows all origins (LAN deployment; tighten in Phase 4 if needed)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:changeme123}")
    private String adminPassword;

    @Value("${operator.username:operator}")
    private String operatorUsername;

    @Value("${operator.password:operator123}")
    private String operatorPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(encoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        UserDetails operator = User.builder()
                .username(operatorUsername)
                .password(encoder.encode(operatorPassword))
                .roles("OPERATOR")
                .build();
        return new InMemoryUserDetailsManager(new ArrayList<>(List.of(admin, operator)));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public read-only
                .requestMatchers(HttpMethod.GET,  "/api/patterns").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/health").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/stats").permitAll()
                // Match requires login (Phase 6: operators must authenticate)
                .requestMatchers(HttpMethod.POST, "/api/match").authenticated()
                // Feedback submission (operator or admin)
                .requestMatchers(HttpMethod.POST, "/api/feedback").authenticated()
                // Feedback image preview — permit so <img> tags load without auth headers
                .requestMatchers(HttpMethod.GET, "/api/admin/feedback/*/image").permitAll()
                // Static SPA assets (served from /static when built)
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                // Image preview endpoint — permit so <img> tags load without auth headers
                .requestMatchers(HttpMethod.GET, "/api/admin/references/*/image").permitAll()
                // Everything else under /api/admin requires authentication
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Any other request requires authentication
                .anyRequest().authenticated()
            )
            .httpBasic(basic -> basic
                .realmName("LeatherMatch-AI Admin"));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
