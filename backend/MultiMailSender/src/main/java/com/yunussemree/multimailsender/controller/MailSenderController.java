package com.yunussemree.multimailsender.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunussemree.multimailsender.model.ApiResponse;
import com.yunussemree.multimailsender.model.ProgressEvent;
import com.yunussemree.multimailsender.model.Request;
import com.yunussemree.multimailsender.service.MailSenderService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class MailSenderController {

    private final MailSenderService mailSenderService;

    // SSE job infra under the same controller
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();

    public MailSenderController(MailSenderService mailSenderService) {
        this.mailSenderService = mailSenderService;
    }
    
     /**     * Endpoint to send multiple emails with attachments based on the provided request.
     *
     * @param request The request containing email details and company data.
     * @param files   The files to be attached to the emails.
     * @return ResponseEntity with a message indicating success or failure.
     */
    @PostMapping(value = "/send-mails-with-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> sendMails(
            @RequestPart("request") Request request,
            @RequestPart(value = "files", required = false) MultipartFile[] files
    ) {
        try {
            mailSenderService.sendEmailsWithAttachments(request, files);
            return ResponseEntity.ok(new ApiResponse("Mails sent successfully", null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ApiResponse("Error expected", e.getMessage()));
        }

    }

    // SSE job-based flow using the same base path
    @PostMapping(value = "/send-mails-with-attachment/start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> startJob(
            @RequestPart("request") Request request,
            @RequestPart(value = "files", required = false) MultipartFile[] files
    ) {
        String jobId = UUID.randomUUID().toString();
        executor.submit(() -> runJobWhenEmitterAvailable(jobId, request, files));
        return ResponseEntity.ok(new ApiResponse("Job started", jobId));
    }

    @GetMapping(value = "/send-mails-with-attachment/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(jobId, emitter);
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        try {
            // send a structured started event including cooldown and total companies for accurate ETA on frontend
            var payload = String.format("{\"minMs\":%d,\"maxMs\":%d}",
                    mailSenderServiceMin(), mailSenderServiceMax());
            emitter.send(SseEmitter.event().name("started").data(payload));
        } catch (IOException ignored) { }
        return emitter;
    }

    private void runJobWhenEmitterAvailable(String jobId, Request request, MultipartFile[] files) {
        int retries = 0;
        while (!emitters.containsKey(jobId) && retries < 50) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            retries++;
        }
        SseEmitter emitter = emitters.get(jobId);
        if (emitter == null) return;
        try {
            // send started again here with totals once connected just in case
            try {
                var payload = String.format("{\"minMs\":%d,\"maxMs\":%d,\"total\":%d}",
                        mailSenderServiceMin(), mailSenderServiceMax(),
                        request.getCompanyData() != null ? request.getCompanyData().size() : 0);
                emitter.send(SseEmitter.event().name("started").data(payload));
            } catch (IOException ignored) {}
            mailSenderService.sendEmailsWithAttachmentsWithCallback(request, files, (idx, evt) -> {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(toJson(evt)));
                } catch (IOException e) {
                }
            });
            try {
                emitter.send(SseEmitter.event().name("finished").data("done"));
            } catch (IOException ignored) {}
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignored) {}
        } finally {
            emitter.complete();
            emitters.remove(jobId);
        }
    }

    private String toJson(ProgressEvent evt) {
        try {
            return mapper.writeValueAsString(evt);
        } catch (Exception e) {
            return "{}";
        }
    }

    // helpers to expose current cooldown config
    private int mailSenderServiceMin() {
        try {
            var f = com.yunussemree.multimailsender.service.MailSenderService.class.getDeclaredField("minCooldownMs");
            f.setAccessible(true);
            return (int) f.get(mailSenderService);
        } catch (Exception ignored) { return 2000; }
    }
    private int mailSenderServiceMax() {
        try {
            var f = com.yunussemree.multimailsender.service.MailSenderService.class.getDeclaredField("maxCooldownMs");
            f.setAccessible(true);
            return (int) f.get(mailSenderService);
        } catch (Exception ignored) { return 12000; }
    }

    /**
     * Health check endpoint to verify if the service is running.
     *
     * @return ResponseEntity with a message indicating the service status.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse> healthCheck() {
        return ResponseEntity.ok(new ApiResponse("Server is running", null));
    }
}
