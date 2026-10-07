package com.yunussemree.multimailsender;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.yunussemree.multimailsender.starter.MultiMailSenderApplication;

@SpringBootTest(classes = MultiMailSenderApplication.class)
@AutoConfigureMockMvc
class MultiMailSenderApplicationTests {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("mail.data-dir", () -> System.getProperty("java.io.tmpdir") + "/mms-test-" + System.nanoTime());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void healthEndpointResponds() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Server is running"));
    }

    @Test
    void invalidRequestIsRejectedWithValidationMessage() throws Exception {
        MockMultipartFile request = new MockMultipartFile("request", "", "application/json",
                "{\"username\":\"nope\",\"password\":\"\",\"subject\":\"\",\"bodydraft\":\"\",\"companyData\":[]}".getBytes());
        mvc.perform(multipart("/send-mails-with-attachment/start").file(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void unknownJobIs404() throws Exception {
        mvc.perform(get("/jobs/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/00000000-0000-0000-0000-000000000000/export.xlsx")).andExpect(status().isNotFound());
    }
}
