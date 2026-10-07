package com.yunussemree.multimailsender.model;

public enum JobStatus {
    RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
