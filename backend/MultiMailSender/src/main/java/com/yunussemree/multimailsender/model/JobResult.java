package com.yunussemree.multimailsender.model;

import java.time.Instant;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome for one recipient of a job. Status: pending | sent | error | skipped. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobResult {
    private int index;
    private String companyMail;
    private String companyName;
    private Map<String, String> parameters;
    private String status;
    private String message;
    private Instant processedAt;
    private Long sendMs;
}
