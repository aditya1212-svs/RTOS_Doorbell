package com.aditya.rtos_doorbell.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "daily_digests", uniqueConstraints = @UniqueConstraint(name = "uk_daily_digest_date", columnNames = "date"))
public class DailyDigest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private LocalDate date;
    @Column(columnDefinition = "TEXT", nullable = false) private String summary;
    protected DailyDigest() {}
    public DailyDigest(LocalDate date, String summary) { this.date = date; this.summary = summary; }
    public LocalDate getDate() { return date; }
    public String getSummary() { return summary; }
    public void updateSummary(String summary) { this.summary = summary; }
}
