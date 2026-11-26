package dev.distill.worker.text;

import dev.distill.worker.ProcessingException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class TextExtractor {

    private static final String PDF = "application/pdf";
    private static final String TEXT = "text/plain";

    public String extract(byte[] content, String contentType, String key) {
        String mediaType = mediaType(contentType, key);
        if (PDF.equals(mediaType)) {
            return extractPdf(content);
        }
        if (TEXT.equals(mediaType)) {
            return new String(content, StandardCharsets.UTF_8);
        }
        throw new ProcessingException("Unsupported content type: " + contentType, false);
    }

    private static String mediaType(String contentType, String key) {
        if (contentType != null && !contentType.isBlank()) {
            int separator = contentType.indexOf(';');
            String base = separator >= 0 ? contentType.substring(0, separator) : contentType;
            return base.trim().toLowerCase(Locale.ROOT);
        }
        String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (lowerKey.endsWith(".pdf")) {
            return PDF;
        }
        if (lowerKey.endsWith(".txt")) {
            return TEXT;
        }
        return "";
    }

    private static String extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException exception) {
            throw new ProcessingException("Could not extract text from PDF", false, exception);
        }
    }
}
