package com.yunussemree.multimailsender.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A bulk-send job. Never contains the sender password. */
@Data
@NoArgsConstructor
public class MailJob {
    private String id;
    private String sender;
    private String subject;
    private Instant createdAt;
    private Instant finishedAt;
    private JobStatus status = JobStatus.RUNNING;
    private String error;
    private int total;
    private int sent;
    private int failed;
    private int skipped;
    private List<JobResult> results = new ArrayList<>();

    @JsonIgnore
    public int getProcessed() {
        return sent + failed + skipped;
    }
}
