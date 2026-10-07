package com.yunussemree.multimailsender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yunussemree.multimailsender.model.CompanyData;
import com.yunussemree.multimailsender.model.JobResult;
import com.yunussemree.multimailsender.model.JobStatus;
import com.yunussemree.multimailsender.model.MailJob;
import com.yunussemree.multimailsender.model.Request;

import jakarta.mail.internet.MimeMessage;

class MailSenderServiceTest {

    @TempDir
    Path dir;

    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private Predicate<String> failFor = to -> false;
    private boolean authFails;
    private MailSenderService service;
    private SentLogService sentLog;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MailSenderFactory factory = new MailSenderFactory() {
            @Override
            public JavaMailSender create(String username, String password) {
                return new JavaMailSenderImpl() {
                    @Override
                    public void send(MimeMessage msg) {
                        if (authFails) throw new MailAuthenticationException("bad credentials");
                        try {
                            String to = msg.getAllRecipients()[0].toString();
                            if (failFor.test(to)) throw new MailSendException("boom " + to);
                            delivered.add(to);
                        } catch (jakarta.mail.MessagingException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };
            }
        };
        sentLog = new SentLogService(mapper, dir.toString());
        service = new MailSenderService(factory, new TemplateRenderer(), sentLog,
                new JobStore(mapper, dir.toString()), mapper, 0, 1, 0);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private Request request(String... mails) {
        Request r = new Request();
        r.setUsername("me@gmail.com");
        r.setPassword("secret");
        r.setSubject("Hi {companyName}");
        r.setBodydraft("Hello {companyName}");
        ArrayList<CompanyData> list = new ArrayList<>();
        int i = 0;
        for (String m : mails) {
            CompanyData c = new CompanyData();
            c.setId(i);
            c.setCompanyMail(m);
            Map<String, String> p = new LinkedHashMap<>();
            p.put("companyName", "Co" + i++);
            c.setParameters(p);
            list.add(c);
        }
        r.setCompanyData(list);
        return r;
    }

    private MailJob await(String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            MailJob job = service.find(id).orElseThrow();
            if (job.getStatus().isTerminal()) return job;
            Thread.sleep(25);
        }
        throw new AssertionError("job did not finish");
    }

    @Test
    void sendsToEveryoneAndRecordsResults() throws Exception {
        MailJob job = await(service.start(request("a@x.com", "b@x.com"), null).getId());
        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals(2, job.getSent());
        assertEquals(List.of("a@x.com", "b@x.com"), delivered);
        assertEquals("Co0", job.getResults().get(0).getCompanyName());
        assertEquals("sent", job.getResults().get(1).getStatus());
    }

    @Test
    void oneFailureDoesNotStopTheRest() throws Exception {
        failFor = to -> to.equals("a@x.com");
        MailJob job = await(service.start(request("a@x.com", "b@x.com"), null).getId());
        assertEquals(1, job.getFailed());
        assertEquals(1, job.getSent());
        assertEquals("error", job.getResults().get(0).getStatus());
        assertTrue(job.getResults().get(0).getMessage().contains("boom"));
    }

    @Test
    void authenticationFailureAbortsTheJob() throws Exception {
        authFails = true;
        MailJob job = await(service.start(request("a@x.com", "b@x.com", "c@x.com"), null).getId());
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(1, job.getFailed());
        assertEquals(2, job.getSkipped());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void skipsMissingParametersAndDuplicates() throws Exception {
        Request r = request("a@x.com", "a@x.com", "c@x.com");
        r.getCompanyData().get(2).getParameters().put("companyName", "");
        MailJob job = await(service.start(r, null).getId());
        assertEquals(1, job.getSent());
        assertEquals(2, job.getSkipped());
        assertTrue(job.getResults().get(1).getMessage().contains("tekrar"));
        assertTrue(job.getResults().get(2).getMessage().contains("companyName"));
    }

    @Test
    void skipsAlreadySentWhenRequested() throws Exception {
        await(service.start(request("a@x.com"), null).getId());
        Request again = request("a@x.com", "b@x.com");
        again.setSkipAlreadySent(true);
        MailJob job = await(service.start(again, null).getId());
        assertEquals(1, job.getSent());
        assertEquals("skipped", job.getResults().get(0).getStatus());
        assertEquals(1, sentLog.countToday("me@gmail.com") - 1);
    }

    @Test
    void jobsSurviveInTheStore() throws Exception {
        MailJob job = await(service.start(request("a@x.com"), null).getId());
        JobStore fresh = new JobStore(new ObjectMapper().registerModule(new JavaTimeModule()), dir.toString());
        MailJob loaded = fresh.find(job.getId()).orElseThrow();
        assertEquals(JobStatus.COMPLETED, loaded.getStatus());
        JobResult r = loaded.getResults().get(0);
        assertNotNull(r.getProcessedAt());
    }

    @Test
    void excelContainsCompanyNameAndStatus() throws Exception {
        failFor = to -> to.equals("b@x.com");
        MailJob job = await(service.start(request("a@x.com", "b@x.com"), null).getId());
        byte[] xlsx = new ExcelExportService().export(job);
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertEquals("Şirket Adı", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Co0", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Gönderildi", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("Hata", sheet.getRow(2).getCell(5).getStringCellValue());
        }
    }

    @Test
    void rejectsSecondConcurrentJobForSameSender() throws Exception {
        failFor = to -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            return false;
        };
        MailJob first = service.start(request("a@x.com"), null);
        assertThrows(MailSenderService.JobRejectedException.class, () -> service.start(request("b@x.com"), null));
        await(first.getId());
    }
}
