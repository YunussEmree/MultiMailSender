package com.yunussemree.multimailsender.model;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompanyData {

    @Min(0)
    private int id;

    @NotBlank
    @Email
    private String companyMail;

    private Map<String, String> parameters = new LinkedHashMap<>();

    public String param(String key) {
        if (parameters == null) return "";
        String v = parameters.get(key);
        return v == null ? "" : v;
    }
}
