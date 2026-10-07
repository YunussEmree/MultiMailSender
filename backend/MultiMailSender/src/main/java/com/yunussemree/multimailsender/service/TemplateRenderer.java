package com.yunussemree.multimailsender.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Replaces {placeholders} in subject/body templates with per-recipient values. */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    /** Placeholders used by the template that have no (or a blank) value. */
    public Set<String> missing(String template, Map<String, String> params) {
        Set<String> missing = new LinkedHashSet<>();
        if (template == null) return missing;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String value = params == null ? null : params.get(m.group(1));
            if (value == null || value.isBlank()) {
                missing.add(m.group(1));
            }
        }
        return missing;
    }

    public String render(String template, Map<String, String> params, boolean escapeHtml) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params == null ? null : params.get(m.group(1));
            String replacement = value == null ? m.group() : value;
            if (value != null && escapeHtml) {
                replacement = HtmlUtils.htmlEscape(value);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
