package com.example.app;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "google_sub")
    public String googleSub;
    public String email;
    public String name;
    public String picture;
    @Column(name = "ingest_token_hash")
    public String ingestTokenHash;
}

interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByGoogleSub(String googleSub);

    Optional<AppUser> findByIngestTokenHash(String ingestTokenHash);
}
