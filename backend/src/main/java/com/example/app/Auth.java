package com.example.app;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    SecretKey jwtKey(@Value("${app.jwt-secret}") String secret) {
        if (secret.length() < 32) throw new IllegalStateException("APP_JWT_SECRET must be at least 32 characters");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/**", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .build();
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    record GoogleLogin(String credential) {}

    private final JwtEncoder encoder;
    private final AppUserRepository users;
    private final String clientId;
    private final NimbusJwtDecoder googleDecoder;

    AuthController(JwtEncoder encoder, AppUserRepository users, @Value("${app.google-client-id}") String clientId) {
        this.encoder = encoder;
        this.users = users;
        this.clientId = clientId;
        this.googleDecoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
        OAuth2TokenValidator<Jwt> googleClaims = jwt -> {
            String iss = jwt.getClaimAsString("iss");
            boolean ok = ("https://accounts.google.com".equals(iss) || "accounts.google.com".equals(iss))
                && jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
            return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        googleDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), googleClaims));
    }

    @GetMapping("/config")
    Map<String, String> config() {
        return Map.of("googleClientId", clientId);
    }

    @PostMapping("/google")
    Map<String, Object> google(@RequestBody GoogleLogin body) {
        if (clientId.isBlank() || body.credential() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Jwt google;
        try {
            google = googleDecoder.decode(body.credential());
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
        }
        AppUser user = users.findByGoogleSub(google.getSubject()).orElseGet(AppUser::new);
        user.googleSub = google.getSubject();
        user.email = google.getClaimAsString("email");
        user.name = google.getClaimAsString("name");
        user.picture = google.getClaimAsString("picture");
        user = users.save(user);

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(String.valueOf(user.id)).issuedAt(now).expiresAt(now.plus(7, ChronoUnit.DAYS)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", user.email);
        profile.put("name", user.name);
        profile.put("picture", user.picture);
        return Map.of("token", token, "user", profile);
    }
}
