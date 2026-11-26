package dev.distill.worker.text;

import dev.distill.worker.ProcessingException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void shouldReadPlainTextAsUtf8() {
        byte[] content = "Documento com acentuação".getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(content, "text/plain", "documents/doc-1/file.txt");

        assertTrue(text.contains("acentuação"));
    }

    @Test
    void shouldExtractTextFromPdf() throws IOException {
        byte[] pdf = pdfWithText("Relatorio de teste");

        String text = extractor.extract(pdf, "application/pdf", "documents/doc-1/file.pdf");

        assertTrue(text.contains("Relatorio de teste"));
    }

    @Test
    void shouldRejectUnsupportedContentType() {
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> extractor.extract(new byte[]{1, 2, 3}, "application/zip", "documents/doc-1/file.zip"));

        assertFalse(exception.isRetryable());
    }

    private static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
