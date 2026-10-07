package com.yunussemree.multimailsender.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.yunussemree.multimailsender.model.ApiResponse;
import com.yunussemree.multimailsender.model.MailJob;
import com.yunussemree.multimailsender.model.Request;
import com.yunussemree.multimailsender.service.ExcelExportService;
import com.yunussemree.multimailsender.service.MailSenderService;

import jakarta.validation.Valid;

@RestController
public class MailSenderController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MailSenderService mailSenderService;
    private final ExcelExportService excelExportService;

    public MailSenderController(MailSenderService mailSenderService, ExcelExportService excelExportService) {
        this.mailSenderService = mailSenderService;
        this.excelExportService = excelExportService;
    }

    /** Starts a background job and returns its id; follow it via the SSE stream endpoint. */
    @PostMapping(value = "/send-mails-with-attachment/start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> startJob(
            @RequestPart("request") @Valid Request request,
            @RequestPart(value = "files", required = false) MultipartFile[] files) throws IOException {
        MailJob job = mailSenderService.start(request, files);
        return ResponseEntity.ok(new ApiResponse("Job started", job.getId()));
    }

    /** Replays everything that already happened and then streams live progress. */
    @GetMapping(value = "/send-mails-with-attachment/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String jobId) {
        return mailSenderService.subscribe(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<ApiResponse> cancel(@PathVariable String jobId) {
        if (mailSenderService.cancel(jobId)) {
            return ResponseEntity.ok(new ApiResponse("Cancellation requested", jobId));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse("Job not found or already finished", jobId));
    }

    @GetMapping("/jobs")
    public List<MailJob> jobs() {
        // list view without the per-recipient rows
        return mailSenderService.list().stream().map(j -> {
            MailJob copy = new MailJob();
            copy.setId(j.getId());
            copy.setSender(j.getSender());
            copy.setSubject(j.getSubject());
            copy.setCreatedAt(j.getCreatedAt());
            copy.setFinishedAt(j.getFinishedAt());
            copy.setStatus(j.getStatus());
            copy.setError(j.getError());
            copy.setTotal(j.getTotal());
            copy.setSent(j.getSent());
            copy.setFailed(j.getFailed());
            copy.setSkipped(j.getSkipped());
            return copy;
        }).toList();
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<MailJob> job(@PathVariable String jobId) {
        return mailSenderService.find(jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{jobId}/export.xlsx")
    public ResponseEntity<byte[]> export(@PathVariable String jobId) throws IOException {
        MailJob job = mailSenderService.find(jobId).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(job.getCreatedAt());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("gonderim-raporu-" + stamp + ".xlsx", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(excelExportService.export(job));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse> healthCheck() {
        return ResponseEntity.ok(new ApiResponse("Server is running", null));
    }
}
