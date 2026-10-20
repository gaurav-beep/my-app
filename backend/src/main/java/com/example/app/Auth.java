package com.example.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
                .requestMatchers("/api/login", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .build();
    }
}

@RestController
class LoginController {
    record Credentials(String username, String password) {}

    private final JwtEncoder encoder;
    private final String user;
    private final String password;

    LoginController(JwtEncoder encoder, @Value("${app.user}") String user, @Value("${app.password}") String password) {
        this.encoder = encoder;
        this.user = user;
        this.password = password;
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(
            (a == null ? "" : a).getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/api/login")
    Map<String, String> login(@RequestBody Credentials c) {
        boolean okUser = same(c.username(), user);
        boolean okPass = same(c.password(), password);
        if (!(okUser & okPass)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(user).issuedAt(now).expiresAt(now.plus(7, ChronoUnit.DAYS)).build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return Map.of("token", encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue());
    }
}
