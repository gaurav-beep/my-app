package com.example.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findBySpentOnBetweenOrderBySpentOnDescIdDesc(LocalDate from, LocalDate to);

    @Query("select e.category as category, sum(e.amount) as total from Expense e where e.spentOn between ?1 and ?2 group by e.category order by sum(e.amount) desc")
    List<CategoryTotal> totals(LocalDate from, LocalDate to);
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

    @GetMapping
    List<Expense> list(@RequestParam(required = false) String month) {
        YearMonth ym = month(month);
        return repo.findBySpentOnBetweenOrderBySpentOnDescIdDesc(ym.atDay(1), ym.atEndOfMonth());
    }

    @GetMapping("/summary")
    List<CategoryTotal> summary(@RequestParam(required = false) String month) {
        YearMonth ym = month(month);
        return repo.totals(ym.atDay(1), ym.atEndOfMonth());
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Expense create(@Valid @RequestBody Expense e) { e.id = null; return repo.save(e); }

    @PutMapping("/{id}")
    Expense update(@PathVariable Long id, @Valid @RequestBody Expense e) {
        Expense x = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        x.description = e.description;
        x.amount = e.amount;
        x.category = e.category;
        x.spentOn = e.spentOn;
        return repo.save(x);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) { repo.deleteById(id); }
}
