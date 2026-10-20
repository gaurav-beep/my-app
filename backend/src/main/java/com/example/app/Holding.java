package com.example.app;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Entity
public class Holding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @JsonIgnore
    @Column(name = "user_id")
    public Long userId;
    @NotBlank @Pattern(regexp = "mf|crypto|stock|coindcx")
    public String type;
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+")
    public String symbol;
    @NotBlank @Size(max = 255)
    public String name;
    @NotNull @Positive
    public BigDecimal units;
    @PositiveOrZero
    public BigDecimal invested;
    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}

interface HoldingRepository extends JpaRepository<Holding, Long> {
    List<Holding> findByUserIdOrderByIdAsc(Long userId);

    Optional<Holding> findByIdAndUserId(Long id, Long userId);

    List<Holding> findByUserIdAndType(Long userId, String type);
}
