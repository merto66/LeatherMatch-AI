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

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;


/**
 * Spring Security configuration (Phase 2 + Phase 6 + Security Hardening).
 *
 * Auth model:
 *   - Public: GET /api/patterns, /api/health, /api/stats (read-only)
 *   - Authenticated: POST /api/match — requires HTTP Basic Auth (operators must log in)
 *   - Protected (ROLE_ADMIN): /api/admin/** — requires HTTP Basic Auth
 *   - CSRF is disabled (stateless REST API over LAN)
 *   - CORS: config-driven with exact origin matching
 *   - Security headers: CSP, X-Frame-Options, HSTS (if SSL enabled)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    @Value("${operator.username}")
    private String operatorUsername;

    @Value("${operator.password}")
    private String operatorPassword;

    @Value("${cors.allowed.origins}")
    private String corsAllowedOrigins;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @PostConstruct
    public void validateCredentials() {
        List<String> errors = new ArrayList<>();

        // Check admin credentials
        if (adminUsername == null || adminUsername.isBlank()) {
            errors.add("admin.username must be set in application.properties");
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            errors.add("admin.password must be set in application.properties");
        } else if (isWeakPassword(adminPassword)) {
            errors.add("admin.password is too weak (must be 12+ characters and not a common password like 'changeme123')");
        }

        // Check operator credentials
        if (operatorUsername == null || operatorUsername.isBlank()) {
            errors.add("operator.username must be set in application.properties");
        }
        if (operatorPassword == null || operatorPassword.isBlank()) {
            errors.add("operator.password must be set in application.properties");
        } else if (isWeakPassword(operatorPassword)) {
            errors.add("operator.password is too weak (must be 12+ characters and not a common password)");
        }

        if (!errors.isEmpty()) {
            String msg = "\n========================================\n" +
                         "SECURITY VALIDATION FAILED\n" +
                         "========================================\n" +
                         String.join("\n", errors) + "\n" +
                         "========================================\n" +
                         "Application startup aborted. Please fix the configuration.\n" +
                         "========================================";
            log.error(msg);
            throw new IllegalStateException("Security validation failed: weak or missing credentials. See log for details.");
        }

        log.info("Credentials validated successfully (admin: {}, operator: {})", adminUsername, operatorUsername);
    }

    private boolean isWeakPassword(String password) {
        if (password.length() < 12) {
            return true;
        }

        // Blacklist common weak passwords
        Set<String> blacklist = Set.of(
            "changeme123", "operator123", "password123", "123456789012",
            "admin123456", "qwerty123456", "letmein12345", "password",
            "adminadmin1", "operatoroper"
        );
        return blacklist.contains(password.toLowerCase());
    }

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

            // Security headers
            .headers(headers -> headers
                .contentTypeOptions(contentTypeOptions -> {})  // X-Content-Type-Options: nosniff (enabled by default)
                .frameOptions(frame -> frame.deny())  // X-Frame-Options: DENY (clickjacking protection)
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                        "default-src 'self'; " +
                        "script-src 'self' 'unsafe-inline'; " +  // 'unsafe-inline' needed for React dev builds
                        "style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data: blob:; " +  // data: URLs and blob: for base64/frontend images
                        "font-src 'self' data:; " +
                        "connect-src 'self'; " +
                        "frame-ancestors 'none'")  // Additional clickjacking protection
                )
                .httpStrictTransportSecurity(hsts -> {
                    if (sslEnabled) {
                        // Only enable HSTS if SSL is configured
                        hsts.maxAgeInSeconds(31536000)  // 1 year
                            .includeSubDomains(false);   // LAN environment, no subdomains
                        log.info("HSTS enabled (SSL detected)");
                    } else {
                        hsts.disable();
                        log.info("HSTS disabled (HTTP-only LAN deployment)");
                    }
                })
                .referrerPolicy(referrer -> referrer
                    .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
                .permissionsPolicy(permissions -> permissions
                    .policy("geolocation=(), camera=(), microphone=(), payment=()")
                )
            )

            .authorizeHttpRequests(auth -> auth
                // Public read-only
                .requestMatchers(HttpMethod.GET,  "/api/patterns").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/health").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/stats").permitAll()
                // Match requires login (Phase 6: operators must authenticate)
                .requestMatchers(HttpMethod.POST, "/api/match").authenticated()
                // Feedback submission (operator or admin)
                .requestMatchers(HttpMethod.POST, "/api/feedback").authenticated()
                // Static SPA assets and root-level files (served from /static when built).
                // All non-/api paths are public because the SPA itself contains no data —
                // data is protected at the /api/** level below.
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/*.png", "/*.jpg", "/*.ico", "/*.js", "/*.css").permitAll()
                // SPA client-side routes: forwarded to index.html by SpaController.
                // Must be permitAll so the browser never receives a Basic-Auth challenge
                // when navigating directly to or refreshing these paths.
                .requestMatchers(HttpMethod.GET, "/login", "/match", "/forbidden", "/admin", "/admin/**").permitAll()
                // Image endpoints now require ADMIN role (removed permitAll)
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
        // Validate at startup
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            throw new IllegalStateException(
                "SECURITY: cors.allowed.origins must be configured in application.properties. " +
                "Example: cors.allowed.origins=http://localhost:3000,http://192.168.1.100:8080");
        }

        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();

        if (origins.isEmpty()) {
            throw new IllegalStateException("SECURITY: cors.allowed.origins contains no valid origins");
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);  // Exact match only, no wildcards
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Cache-Control"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);  // Cache preflight response for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        log.info("CORS configured with allowed origins: {}", origins);
        return source;
    }
}
