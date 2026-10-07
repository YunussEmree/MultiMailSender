package com.yunussemree.multimailsender.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistent record of successfully sent mails. Used to skip recipients that were
 * already contacted and to enforce a per-day sending limit.
 */
@Service
public class SentLogService {

    private static final Logger log = LoggerFactory.getLogger(SentLogService.class);

    public record Entry(String sender, String recipient, Instant at) {}

    private final ObjectMapper mapper;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public SentLogService(ObjectMapper mapper, @Value("${mail.data-dir:data}") String dataDir) {
        this.mapper = mapper;
        this.file = Path.of(dataDir, "sent-log.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            entries.addAll(mapper.readValue(file.toFile(), new TypeReference<List<Entry>>() {}));
        } catch (IOException e) {
            log.warn("Could not read sent log {}: {}", file, e.getMessage());
        }
    }

    public synchronized boolean hasSent(String sender, String recipient) {
        String s = norm(sender), r = norm(recipient);
        return entries.stream().anyMatch(e -> e.sender().equals(s) && e.recipient().equals(r));
    }

    public synchronized long countToday(String sender) {
        String s = norm(sender);
        LocalDate today = LocalDate.now();
        return entries.stream()
                .filter(e -> e.sender().equals(s))
                .filter(e -> LocalDate.ofInstant(e.at(), ZoneId.systemDefault()).equals(today))
                .count();
    }

    public synchronized void record(String sender, String recipient) {
        entries.add(new Entry(norm(sender), norm(recipient), Instant.now()));
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), entries);
        } catch (IOException e) {
            log.warn("Could not write sent log {}: {}", file, e.getMessage());
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
