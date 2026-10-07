package com.yunussemree.multimailsender.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunussemree.multimailsender.model.JobStatus;
import com.yunussemree.multimailsender.model.MailJob;

/** Stores job reports as JSON files so they survive restarts and can be exported later. */
@Service
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    private final ObjectMapper mapper;
    private final Path dir;

    public JobStore(ObjectMapper mapper, @Value("${mail.data-dir:data}") String dataDir) {
        this.mapper = mapper;
        this.dir = Path.of(dataDir, "jobs");
        markInterrupted();
    }

    public synchronized void save(MailJob job) {
        try {
            Files.createDirectories(dir);
            mapper.writeValue(dir.resolve(job.getId() + ".json").toFile(), job);
        } catch (IOException e) {
            log.warn("Could not persist job {}: {}", job.getId(), e.getMessage());
        }
    }

    public synchronized Optional<MailJob> find(String id) {
        // ids are server-generated UUIDs; reject anything else so an id cannot escape the directory
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return Optional.empty();
        Path p = dir.resolve(id + ".json");
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(p.toFile(), MailJob.class));
        } catch (IOException e) {
            log.warn("Could not read job {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized List<MailJob> list() {
        List<MailJob> jobs = new ArrayList<>();
        if (!Files.isDirectory(dir)) return jobs;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    jobs.add(mapper.readValue(f.toFile(), MailJob.class));
                } catch (IOException e) {
                    log.warn("Skipping unreadable job file {}", f);
                }
            });
        } catch (IOException e) {
            log.warn("Could not list jobs: {}", e.getMessage());
        }
        jobs.sort(Comparator.comparing(MailJob::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return jobs;
    }

    /** Jobs still marked RUNNING on disk belong to a previous process that died. */
    private void markInterrupted() {
        for (MailJob job : list()) {
            if (job.getStatus() == JobStatus.RUNNING) {
                job.setStatus(JobStatus.INTERRUPTED);
                job.setFinishedAt(Instant.now());
                save(job);
            }
        }
    }
}
