package com.yunussemree.multimailsender.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single mail sending progress update sent to the frontend (SSE).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressEvent {

    private int index;
    private String companyMail;
    private String email;
    private String status;
    private String message;
    private Long sendMs;
    private Long cooldownMs;
    private Long durationMs;
    private Long plannedCooldownMs;

    public ProgressEvent(int index, String companyMail, String status, String message) {
        this.index = index;
        this.companyMail = companyMail;
        this.status = status;
        this.message = message;
    }

    public ProgressEvent(
            int index,
            String companyMail,
            String status,
            String message,
            long sendMs,
            long plannedCooldownMs
    ) {
        this.index = index;
        this.companyMail = companyMail;
        this.status = status;
        this.message = message;
        this.sendMs = sendMs;
        this.plannedCooldownMs = plannedCooldownMs;
    }
}
