package com.yunussemree.multimailsender.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunussemree.multimailsender.model.CompanyData;
import com.yunussemree.multimailsender.model.InMemoryMultipartFile;
import com.yunussemree.multimailsender.model.JobResult;
import com.yunussemree.multimailsender.model.JobStatus;
import com.yunussemree.multimailsender.model.MailJob;
import com.yunussemree.multimailsender.model.ProgressEvent;
import com.yunussemree.multimailsender.model.Request;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Runs bulk-send jobs in the background, streams progress to subscribers over SSE
 * and persists every job report so it can be exported afterwards.
 */
@Service
public class MailSenderService {

    private static final Logger log = LoggerFactory.getLogger(MailSenderService.class);
    private static final int MAX_ATTEMPTS = 2;

    /** Raised for requests that must be rejected with a 4xx status. */
    public static class JobRejectedException extends RuntimeException {
        public JobRejectedException(String message) {
            super(message);
        }
    }

    private static final class Runtime {
        final MailJob job;
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;
        volatile Thread worker;

        Runtime(MailJob job) {
            this.job = job;
        }
    }

    private final MailSenderFactory senderFactory;
    private final TemplateRenderer renderer;
    private final SentLogService sentLog;
    private final JobStore store;
    private final ObjectMapper mapper;
    private final int minCooldownMs;
    private final int maxCooldownMs;
    private final int dailyLimit;

    private final Random random = new Random();
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public MailSenderService(
            MailSenderFactory senderFactory,
            TemplateRenderer renderer,
            SentLogService sentLog,
            JobStore store,
            ObjectMapper mapper,
            @Value("${mail.cooldown.minMs:2000}") int minCooldownMs,
            @Value("${mail.cooldown.maxMs:12000}") int maxCooldownMs,
            @Value("${mail.daily-limit:450}") int dailyLimit) {
        this.senderFactory = senderFactory;
        this.renderer = renderer;
        this.sentLog = sentLog;
        this.store = store;
        this.mapper = mapper;
        this.minCooldownMs = Math.max(0, minCooldownMs);
        this.maxCooldownMs = Math.max(this.minCooldownMs + 1, maxCooldownMs);
        this.dailyLimit = dailyLimit;
    }

    public int getMinCooldownMs() {
        return minCooldownMs;
    }

    public int getMaxCooldownMs() {
        return maxCooldownMs;
    }

    // ------------------------------------------------------------------ public API

    /** Registers a job and runs it on a worker thread. Returns immediately. */
    public MailJob start(Request request, MultipartFile[] files) throws IOException {
        String sender = request.getUsername().trim().toLowerCase();
        boolean busy = runtimes.values().stream()
                .anyMatch(r -> r.job.getStatus() == JobStatus.RUNNING && sender.equalsIgnoreCase(r.job.getSender()));
        if (busy) {
            throw new JobRejectedException("Bu hesap için zaten çalışan bir gönderim var.");
        }

        MultipartFile[] attachments = copyAttachments(files);

        MailJob job = new MailJob();
        job.setId(java.util.UUID.randomUUID().toString());
        job.setSender(request.getUsername());
        job.setSubject(request.getSubject());
        job.setCreatedAt(Instant.now());
        job.setTotal(request.getCompanyData().size());
        int idx = 0;
        for (CompanyData c : request.getCompanyData()) {
            job.getResults().add(new JobResult(idx++, c.getCompanyMail(), c.param("companyName"),
                    new LinkedHashMap<>(c.getParameters() == null ? Map.of() : c.getParameters()),
                    "pending", null, null, null));
        }

        Runtime rt = new Runtime(job);
        runtimes.put(job.getId(), rt);
        store.save(job);
        executor.submit(() -> run(rt, request, attachments));
        return job;
    }

    /** Requests cancellation. Returns false when the job is unknown or already finished. */
    public boolean cancel(String jobId) {
        Runtime rt = runtimes.get(jobId);
        if (rt == null || rt.job.getStatus().isTerminal()) return false;
        rt.cancelled = true;
        Thread w = rt.worker;
        if (w != null) w.interrupt();
        return true;
    }

    public Optional<MailJob> find(String jobId) {
        Runtime rt = runtimes.get(jobId);
        if (rt != null) {
            synchronized (rt) {
                return Optional.of(snapshot(rt.job));
            }
        }
        return store.find(jobId);
    }

    public List<MailJob> list() {
        List<MailJob> stored = store.list();
        // prefer the live view for jobs that are still running
        return stored.stream().map(j -> {
            Runtime rt = runtimes.get(j.getId());
            return rt != null ? find(j.getId()).orElse(j) : j;
        }).toList();
    }

    /** Opens an SSE stream that first replays what already happened, then follows live events. */
    public Optional<SseEmitter> subscribe(String jobId) {
        Runtime rt = runtimes.get(jobId);
        if (rt == null) {
            return store.find(jobId).map(this::replayFinished);
        }
        SseEmitter emitter = new SseEmitter(0L);
        synchronized (rt) {
            Runnable cleanup = () -> rt.subscribers.remove(emitter);
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(e -> cleanup.run());
            try {
                emitter.send(SseEmitter.event().name("started").data(startedPayload(rt.job)));
                for (JobResult r : rt.job.getResults()) {
                    if (!"pending".equals(r.getStatus())) {
                        emitter.send(SseEmitter.event().name("progress").data(toJson(toEvent(r, null))));
                    }
                }
                if (rt.job.getStatus().isTerminal()) {
                    emitter.send(SseEmitter.event().name("finished").data(finishedPayload(rt.job)));
                    emitter.complete();
                } else {
                    rt.subscribers.add(emitter);
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
        return Optional.of(emitter);
    }

    @PreDestroy
    void shutdown() {
        runtimes.values().forEach(rt -> {
            rt.cancelled = true;
            Thread w = rt.worker;
            if (w != null) w.interrupt();
        });
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ job execution

    private void run(Runtime rt, Request request, MultipartFile[] attachments) {
        MailJob job = rt.job;
        rt.worker = Thread.currentThread();
        JavaMailSender sender = senderFactory.create(request.getUsername(), request.getPassword());
        Set<String> seen = new HashSet<>();
        int count = request.getCompanyData().size();
        JobStatus finalStatus = JobStatus.COMPLETED;

        publish(rt, "started", startedPayload(job));
        try {
            for (int i = 0; i < count; i++) {
                if (rt.cancelled) {
                    finalStatus = JobStatus.CANCELLED;
                    break;
                }
                CompanyData company = request.getCompanyData().get(i);
                JobResult result = job.getResults().get(i);
                long cooldown = 0;
                try {
                    boolean sent = processOne(sender, request, company, attachments, result, seen);
                    publishResult(rt, result);
                    if (sent && i < count - 1) {
                        cooldown = randomCooldownMs();
                    }
                } catch (MailAuthenticationException e) {
                    finish(rt, result, "error", "Gmail kimlik doğrulaması başarısız. Uygulama şifresini kontrol edin.", null);
                    abortRemaining(rt, i + 1, "Kimlik doğrulama hatası nedeniyle gönderilmedi.");
                    job.setError("Gmail kimlik doğrulaması başarısız.");
                    finalStatus = JobStatus.FAILED;
                    break;
                }
                if (cooldown > 0) {
                    Thread.sleep(cooldown);
                }
            }
        } catch (InterruptedException e) {
            finalStatus = JobStatus.CANCELLED;
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Job {} failed", job.getId(), e);
            job.setError(e.getMessage());
            finalStatus = JobStatus.FAILED;
        } finally {
            rt.worker = null;
            if (finalStatus == JobStatus.CANCELLED) {
                Thread.interrupted(); // clear the flag so persistence below is not disturbed
            }
            synchronized (rt) {
                job.setStatus(finalStatus);
                job.setFinishedAt(Instant.now());
                store.save(job);
            }
            publish(rt, "finished", finishedPayload(job));
            rt.subscribers.forEach(SseEmitter::complete);
            rt.subscribers.clear();
        }
    }

    /** @return true when a mail was actually sent (so the caller should cool down). */
    private boolean processOne(JavaMailSender sender, Request request, CompanyData company,
                               MultipartFile[] attachments, JobResult result, Set<String> seen) throws InterruptedException {
        Map<String, String> params = company.getParameters();
        String mail = company.getCompanyMail().trim();

        Set<String> missing = new java.util.LinkedHashSet<>(renderer.missing(request.getBodydraft(), params));
        missing.addAll(renderer.missing(request.getSubject(), params));
        if (!missing.isEmpty()) {
            finish(result, "skipped", "Eksik parametre: " + String.join(", ", missing), null);
            return false;
        }
        if (!seen.add(mail.toLowerCase())) {
            finish(result, "skipped", "Listede tekrar eden adres.", null);
            return false;
        }
        if (request.isSkipAlreadySent() && sentLog.hasSent(request.getUsername(), mail)) {
            finish(result, "skipped", "Daha önce gönderilmiş.", null);
            return false;
        }
        if (dailyLimit > 0 && sentLog.countToday(request.getUsername()) >= dailyLimit) {
            finish(result, "skipped", "Günlük gönderim limitine (" + dailyLimit + ") ulaşıldı.", null);
            return false;
        }

        long t0 = System.currentTimeMillis();
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                MimeMessage message = buildMessage(sender, request, company, attachments);
                sender.send(message);
                long took = System.currentTimeMillis() - t0;
                sentLog.record(request.getUsername(), mail);
                finish(result, "sent", "Gönderildi", took);
                return true;
            } catch (MailAuthenticationException e) {
                throw e;
            } catch (MailException | jakarta.mail.MessagingException | IOException e) {
                lastError = e.getMessage();
                log.warn("Send to {} failed (attempt {}/{}): {}", mail, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) Thread.sleep(1500L * attempt);
            }
        }
        finish(result, "error", lastError == null ? "Bilinmeyen hata" : lastError, System.currentTimeMillis() - t0);
        return false;
    }

    private MimeMessage buildMessage(JavaMailSender sender, Request request, CompanyData company,
                                     MultipartFile[] attachments) throws jakarta.mail.MessagingException, IOException {
        Map<String, String> params = company.getParameters();
        String body = renderer.render(request.getBodydraft(), params, request.isHtml());
        String subject = renderer.render(request.getSubject(), params, false);

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        if (request.getFromName() != null && !request.getFromName().isBlank()) {
            helper.setFrom(new InternetAddress(request.getUsername(), request.getFromName().trim(), StandardCharsets.UTF_8.name()));
        } else {
            helper.setFrom(request.getUsername());
        }
        helper.setTo(company.getCompanyMail().trim());
        helper.setSubject(subject);
        helper.setText(body, request.isHtml());
        if (attachments != null) {
            for (MultipartFile file : attachments) {
                if (file != null && !file.isEmpty() && file.getOriginalFilename() != null) {
                    helper.addAttachment(file.getOriginalFilename(), file);
                }
            }
        }
        return message;
    }

    private void abortRemaining(Runtime rt, int from, String reason) {
        for (int j = from; j < rt.job.getResults().size(); j++) {
            finish(rt, rt.job.getResults().get(j), "skipped", reason, null);
        }
    }

    // ------------------------------------------------------------------ result bookkeeping

    private void finish(JobResult r, String status, String message, Long sendMs) {
        r.setStatus(status);
        r.setMessage(message);
        r.setSendMs(sendMs);
        r.setProcessedAt(Instant.now());
    }

    private void finish(Runtime rt, JobResult r, String status, String message, Long sendMs) {
        finish(r, status, message, sendMs);
        publishResult(rt, r);
    }

    private void publishResult(Runtime rt, JobResult r) {
        synchronized (rt) {
            MailJob job = rt.job;
            job.setSent((int) job.getResults().stream().filter(x -> "sent".equals(x.getStatus())).count());
            job.setFailed((int) job.getResults().stream().filter(x -> "error".equals(x.getStatus())).count());
            job.setSkipped((int) job.getResults().stream().filter(x -> "skipped".equals(x.getStatus())).count());
            store.save(job);
            publishLocked(rt, "progress", toJson(toEvent(r, null)));
        }
    }

    // ------------------------------------------------------------------ SSE helpers

    private void publish(Runtime rt, String event, String data) {
        synchronized (rt) {
            publishLocked(rt, event, data);
        }
    }

    private void publishLocked(Runtime rt, String event, String data) {
        for (SseEmitter emitter : rt.subscribers) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                rt.subscribers.remove(emitter);
            }
        }
    }

    private SseEmitter replayFinished(MailJob job) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("started").data(startedPayload(job)));
            for (JobResult r : job.getResults()) {
                if (!"pending".equals(r.getStatus())) {
                    emitter.send(SseEmitter.event().name("progress").data(toJson(toEvent(r, null))));
                }
            }
            emitter.send(SseEmitter.event().name("finished").data(finishedPayload(job)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private ProgressEvent toEvent(JobResult r, Long plannedCooldown) {
        return ProgressEvent.builder()
                .index(r.getIndex())
                .companyMail(r.getCompanyMail())
                .companyName(r.getCompanyName())
                .status(r.getStatus())
                .message(r.getMessage())
                .sendMs(r.getSendMs())
                .plannedCooldownMs(plannedCooldown)
                .build();
    }

    private String startedPayload(MailJob job) {
        return toJson(Map.of("jobId", job.getId(), "total", job.getTotal(),
                "minMs", minCooldownMs, "maxMs", maxCooldownMs));
    }

    private String finishedPayload(MailJob job) {
        return toJson(Map.of("status", job.getStatus().name(), "sent", job.getSent(),
                "failed", job.getFailed(), "skipped", job.getSkipped(),
                "error", job.getError() == null ? "" : job.getError()));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private MailJob snapshot(MailJob job) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(job), MailJob.class);
        } catch (IOException e) {
            return job;
        }
    }

    // ------------------------------------------------------------------ misc

    private MultipartFile[] copyAttachments(MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) return null;
        MultipartFile[] copy = new MultipartFile[files.length];
        for (int i = 0; i < files.length; i++) {
            copy[i] = new InMemoryMultipartFile(files[i]);
        }
        return copy;
    }

    private int randomCooldownMs() {
        return minCooldownMs + random.nextInt(maxCooldownMs - minCooldownMs);
    }
}
