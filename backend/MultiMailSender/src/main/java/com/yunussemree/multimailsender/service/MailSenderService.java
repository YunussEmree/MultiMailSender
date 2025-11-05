package com.yunussemree.multimailsender.service;

import java.util.HashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.yunussemree.multimailsender.model.ProgressEvent;
import com.yunussemree.multimailsender.model.Request;

import jakarta.mail.internet.MimeMessage;

@Service
public class MailSenderService {

    Random randomGenerator = new Random();

    @Value("${mail.cooldown.minMs:2000}")
    public int minCooldownMs;

    @Value("${mail.cooldown.maxMs:12000}")
    public int maxCooldownMs;

    private final JavaMailSenderImpl mailSender;

    public MailSenderService() {
        this.mailSender = new JavaMailSenderImpl();
        this.mailSender.setHost("smtp.gmail.com");
        this.mailSender.setPort(587);

        Properties props = this.mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
    }

    public void sendEmailsWithAttachments(Request request, MultipartFile[] files) throws Exception {
        this.mailSender.setUsername(request.getUsername());
        this.mailSender.setPassword(request.getPassword());

        for (var company : request.getCompanyData()) {
            HashMap<String, String> parameters = company.getParameters();
            String body = request.getBodydraft();
            for (String key : parameters.keySet()) {
                body = body.replace("{" + key + "}", parameters.get(key));
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setTo(company.getCompanyMail());
            helper.setSubject(request.getSubject());
            helper.setText(body);

            if (files != null) {
                for (MultipartFile file : files) {
                    if (file != null && !file.isEmpty()) {
                        helper.addAttachment(Objects.requireNonNull(file.getOriginalFilename()), file);
                    }
                }
            }

            int randomint = getRandomCooldownMs();

            mailSender.send(message);
            Thread.sleep(randomint); // cooldown to avoid rate limiting
        }
    }

    public void sendEmailsWithAttachmentsWithCallback(Request request, MultipartFile[] files, java.util.function.BiConsumer<Integer, ProgressEvent> onProgress) throws Exception {
        this.mailSender.setUsername(request.getUsername());
        this.mailSender.setPassword(request.getPassword());

        int index = 0;
        for (var company : request.getCompanyData()) {
            try {
                HashMap<String, String> parameters = company.getParameters();
                String body = request.getBodydraft();
                for (String key : parameters.keySet()) {
                    body = body.replace("{" + key + "}", parameters.get(key));
                }

                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true);

                helper.setTo(company.getCompanyMail());
                helper.setSubject(request.getSubject());
                helper.setText(body);

                if (files != null) {
                    for (MultipartFile file : files) {
                        if (file != null && !file.isEmpty()) {
                            helper.addAttachment(Objects.requireNonNull(file.getOriginalFilename()), file);
                        }
                    }
                }

                long t0 = System.currentTimeMillis();
                mailSender.send(message);
                long t1 = System.currentTimeMillis();
                int plannedCooldown = getRandomCooldownMs();
                if (onProgress != null) {
                    onProgress.accept(index, new ProgressEvent(index, company.getCompanyMail(), "sent", "Mail sent", (t1 - t0), plannedCooldown));
                }
                Thread.sleep(plannedCooldown);
                System.out.println("Mail to " + company.getCompanyMail() + " processed, cooldown " + plannedCooldown + "ms.");
            } catch (Exception e) {
                if (onProgress != null) {
                    onProgress.accept(index, new ProgressEvent(index, company.getCompanyMail(), "error", e.getMessage()));
                }
            }
            index++;
        }
    }

    private int getRandomCooldownMs() {
        int min = Math.max(0, minCooldownMs);
        int max = Math.max(min + 1, maxCooldownMs);
        return min + randomGenerator.nextInt(max - min);
    }

}
