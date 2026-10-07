package com.yunussemree.multimailsender.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import com.yunussemree.multimailsender.model.JobResult;
import com.yunussemree.multimailsender.model.MailJob;

/** Builds the .xlsx delivery report of a job (company, status, detail, ...). */
@Service
public class ExcelExportService {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String[] HEADERS = {
            "#", "Şirket Adı", "E-posta", "Telefon", "Website", "Durum", "Detay / Hata", "İşlem Zamanı", "Süre (ms)"
    };
    private static final int[] WIDTHS = {6, 38, 34, 18, 34, 14, 50, 20, 11};

    public byte[] export(MailJob job) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(wb);
            CellStyle text = bordered(wb, null);
            CellStyle sent = bordered(wb, IndexedColors.LIGHT_GREEN);
            CellStyle error = bordered(wb, IndexedColors.CORAL);
            CellStyle skipped = bordered(wb, IndexedColors.LIGHT_YELLOW);
            CellStyle pending = bordered(wb, IndexedColors.GREY_25_PERCENT);

            Sheet sheet = wb.createSheet("Gönderim Raporu");
            Row hr = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(header);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            int rowNum = 1;
            for (JobResult r : job.getResults()) {
                Row row = sheet.createRow(rowNum++);
                Map<String, String> p = r.getParameters() == null ? Map.of() : r.getParameters();
                set(row, 0, r.getIndex() + 1, text);
                set(row, 1, r.getCompanyName(), text);
                set(row, 2, r.getCompanyMail(), text);
                set(row, 3, p.getOrDefault("companyNumber", ""), text);
                set(row, 4, p.getOrDefault("companyWebsite", ""), text);
                set(row, 5, statusLabel(r.getStatus()), switch (r.getStatus() == null ? "" : r.getStatus()) {
                    case "sent" -> sent;
                    case "error" -> error;
                    case "skipped" -> skipped;
                    default -> pending;
                });
                set(row, 6, r.getMessage(), text);
                set(row, 7, r.getProcessedAt() == null ? "" : TS.format(r.getProcessedAt()), text);
                if (r.getSendMs() != null) {
                    set(row, 8, r.getSendMs(), text);
                } else {
                    set(row, 8, "", text);
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, rowNum - 1), 0, HEADERS.length - 1));

            Sheet summary = wb.createSheet("Özet");
            summary.setColumnWidth(0, 24 * 256);
            summary.setColumnWidth(1, 40 * 256);
            String[][] rows = {
                    {"Gönderen", job.getSender()},
                    {"Konu", job.getSubject()},
                    {"Başlangıç", job.getCreatedAt() == null ? "" : TS.format(job.getCreatedAt())},
                    {"Bitiş", job.getFinishedAt() == null ? "" : TS.format(job.getFinishedAt())},
                    {"İş durumu", jobStatusLabel(job)},
                    {"Toplam", String.valueOf(job.getTotal())},
                    {"Gönderildi", String.valueOf(job.getSent())},
                    {"Hata", String.valueOf(job.getFailed())},
                    {"Atlandı", String.valueOf(job.getSkipped())},
                    {"Beklemede", String.valueOf(job.getTotal() - job.getProcessed())},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = summary.createRow(i);
                set(r, 0, rows[i][0], header);
                set(r, 1, rows[i][1], text);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    static String statusLabel(String status) {
        if (status == null) return "Beklemede";
        return switch (status) {
            case "sent" -> "Gönderildi";
            case "error" -> "Hata";
            case "skipped" -> "Atlandı";
            default -> "Beklemede";
        };
    }

    private static String jobStatusLabel(MailJob job) {
        return switch (job.getStatus()) {
            case RUNNING -> "Devam ediyor";
            case COMPLETED -> "Tamamlandı";
            case CANCELLED -> "İptal edildi";
            case FAILED -> "Başarısız";
            case INTERRUPTED -> "Yarıda kesildi";
        };
    }

    private static void set(Row row, int col, Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else {
            c.setCellValue(value == null ? "" : value.toString());
        }
        c.setCellStyle(style);
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        return s;
    }

    private static CellStyle bordered(Workbook wb, IndexedColors fill) {
        CellStyle s = wb.createCellStyle();
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setWrapText(true);
        s.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        if (fill != null) {
            s.setFillForegroundColor(fill.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }
}
