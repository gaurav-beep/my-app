package com.example.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}

@Entity
class Task {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @NotBlank
    public String title;
    public boolean done;
    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}

interface TaskRepository extends JpaRepository<Task, Long> {}

@RestController
@RequestMapping("/api/tasks")
class TaskController {
    private final TaskRepository repo;

    TaskController(TaskRepository repo) { this.repo = repo; }

    @GetMapping
    List<Task> list() { return repo.findAll(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Task create(@Valid @RequestBody Task t) { t.id = null; return repo.save(t); }

    @PutMapping("/{id}")
    Task update(@PathVariable Long id, @Valid @RequestBody Task t) {
        Task e = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        e.title = t.title;
        e.done = t.done;
        return repo.save(e);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) { repo.deleteById(id); }
}
