package com.example.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByUserIdAndSpentOnBetweenOrderBySpentOnDescIdDesc(Long userId, LocalDate from, LocalDate to);

    Optional<Expense> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndExternalRef(Long userId, String externalRef);

    @Query("select e.category as category, sum(e.amount) as total from Expense e where e.userId = ?1 and e.spentOn between ?2 and ?3 group by e.category order by sum(e.amount) desc")
    List<CategoryTotal> totals(Long userId, LocalDate from, LocalDate to);
}

interface CategoryTotal {
    String getCategory();
    BigDecimal getTotal();
}

@RestController
@RequestMapping("/api/expenses")
class ExpenseController {
    private final ExpenseRepository repo;

    ExpenseController(ExpenseRepository repo) { this.repo = repo; }

    private static YearMonth month(String m) {
        try {
            return m == null ? YearMonth.now() : YearMonth.parse(m);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
    }

    private static Long uid(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private Expense owned(Long id, Jwt jwt) {
        return repo.findByIdAndUserId(id, uid(jwt)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    List<Expense> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        YearMonth ym = month(month);
        return repo.findByUserIdAndSpentOnBetweenOrderBySpentOnDescIdDesc(uid(jwt), ym.atDay(1), ym.atEndOfMonth());
    }

    @GetMapping("/summary")
    List<CategoryTotal> summary(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        YearMonth ym = month(month);
        return repo.totals(uid(jwt), ym.atDay(1), ym.atEndOfMonth());
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Expense create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Expense e) {
        e.id = null;
        e.userId = uid(jwt);
        e.source = "manual";
        e.externalRef = null;
        return repo.save(e);
    }

    @PutMapping("/{id}")
    Expense update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody Expense e) {
        Expense x = owned(id, jwt);
        x.description = e.description;
        x.amount = e.amount;
        x.category = e.category;
        x.spentOn = e.spentOn;
        return repo.save(x);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { repo.delete(owned(id, jwt)); }
}
