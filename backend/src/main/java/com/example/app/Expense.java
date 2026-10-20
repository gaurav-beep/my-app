package com.example.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Entity
public class Expense {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @NotBlank @Size(max = 255)
    public String description;
    @NotNull @Positive
    public BigDecimal amount;
    @NotBlank @Size(max = 50)
    public String category;
    @NotNull
    @Column(name = "spent_on")
    public LocalDate spentOn;
    @JsonIgnore
    @Column(name = "user_id")
    public Long userId;
    @JsonIgnore
    @Column(name = "external_ref")
    public String externalRef;
    public String source = "manual";
    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
