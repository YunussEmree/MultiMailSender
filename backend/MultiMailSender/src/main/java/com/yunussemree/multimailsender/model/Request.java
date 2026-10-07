package com.yunussemree.multimailsender.model;

import java.util.ArrayList;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class Request {

    @NotBlank
    @Email
    @Size(max = 255)
    private String username;

    @NotBlank
    @Size(max = 255)
    @ToString.Exclude
    private String password;

    /** Optional display name shown in the "From" header. */
    @Size(max = 255)
    private String fromName;

    @NotBlank
    @Size(max = 255)
    private String subject;

    @NotBlank
    @Size(max = 20000)
    private String bodydraft;

    /** When true the rendered body is sent as HTML, otherwise as plain text. */
    private boolean html;

    /** When true recipients that were already mailed successfully by this sender are skipped. */
    private boolean skipAlreadySent;

    @Valid
    @NotEmpty
    private ArrayList<CompanyData> companyData;
}
