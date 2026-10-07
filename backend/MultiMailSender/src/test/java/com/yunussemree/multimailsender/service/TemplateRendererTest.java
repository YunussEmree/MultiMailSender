package com.yunussemree.multimailsender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void replacesAllPlaceholders() {
        String out = renderer.render("Merhaba {companyName}, {companyName} / {phone}",
                Map.of("companyName", "Acme", "phone", "123"), false);
        assertEquals("Merhaba Acme, Acme / 123", out);
    }

    @Test
    void keepsUnknownPlaceholders() {
        assertEquals("Hi {x}", renderer.render("Hi {x}", Map.of(), false));
    }

    @Test
    void treatsDollarAndBackslashInValuesLiterally() {
        assertEquals("A$1\\B", renderer.render("{v}", Map.of("v", "A$1\\B"), false));
    }

    @Test
    void escapesHtmlValuesOnlyWhenAsked() {
        assertEquals("<b>Tom &amp; Jerry</b>", renderer.render("<b>{n}</b>", Map.of("n", "Tom & Jerry"), true));
        assertEquals("<b>Tom & Jerry</b>", renderer.render("<b>{n}</b>", Map.of("n", "Tom & Jerry"), false));
    }

    @Test
    void reportsMissingAndBlankPlaceholders() {
        var missing = renderer.missing("{a} {b} {c}", Map.of("a", "x", "b", "  "));
        assertTrue(missing.contains("b"));
        assertTrue(missing.contains("c"));
        assertEquals(2, missing.size());
    }
}
