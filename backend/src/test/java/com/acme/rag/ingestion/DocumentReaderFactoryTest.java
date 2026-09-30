package com.acme.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

class DocumentReaderFactoryTest {

  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private final DocumentReaderFactory factory = new DocumentReaderFactory();

  @TempDir Path tempDir;

  @Test
  void chunksOfAThreePagePdfCarryTheirPageNumber() throws IOException { // AC3.3
    Path pdf = threePagePdf();

    List<Document> pages = factory.read(pdf, "application/pdf");
    List<Document> chunks = new OverlappingTokenSplitter(20, 5).apply(pages);

    assertThat(pages).hasSize(3);
    assertThat(chunks).hasSizeGreaterThan(3); // plusieurs chunks par page
    assertThat(chunks)
        .extracting(chunk -> chunk.getMetadata().get(DocumentReaderFactory.PAGE))
        .containsOnly(1, 2, 3)
        .contains(1, 2, 3);
    assertThat(chunks)
        .filteredOn(chunk -> chunk.getText().contains("deuxième"))
        .allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("page", 2));
  }

  @Test
  void readsTextAndMarkdownWithoutPage() throws IOException {
    for (String name : List.of("sample.txt", "sample.md")) {
      List<Document> documents = factory.read(fixture(name), "text/plain");

      assertThat(documents)
          .singleElement()
          .satisfies(
              document -> {
                assertThat(document.getText()).isNotBlank();
                assertThat(document.getMetadata()).isEmpty();
              });
    }
  }

  @Test
  void readsDocx() throws IOException {
    List<Document> documents = factory.read(fixture("sample.docx"), DOCX);

    assertThat(documents).isNotEmpty().allSatisfy(d -> assertThat(d.getText()).isNotBlank());
  }

  @Test
  void corruptedPdfFails() throws IOException { // cause de FAILED (AC3.2)
    assertThatThrownBy(() -> factory.read(fixture("corrupt.pdf"), "application/pdf"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void unsupportedTypeIsRejected() {
    assertThatThrownBy(() -> factory.read(tempDir.resolve("x.bin"), "application/zip"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Généré avec PDFBox plutôt que commité : le contenu attendu de chaque page est visible ici. */
  private Path threePagePdf() throws IOException {
    Path file = tempDir.resolve("trois-pages.pdf");
    String[] ordinals = {"première", "deuxième", "troisième"};
    try (PDDocument pdf = new PDDocument()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (String ordinal : ordinals) {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
          content.beginText();
          content.setFont(font, 12);
          content.setLeading(16);
          content.newLineAtOffset(50, 700);
          for (int line = 1; line <= 6; line++) {
            content.showText("Ceci est la ligne " + line + " de la " + ordinal + " page du guide.");
            content.newLine();
          }
          content.endText();
        }
      }
      pdf.save(file.toFile());
    }
    return file;
  }

  private Path fixture(String name) throws IOException {
    Path target = tempDir.resolve(name);
    try (InputStream in = getClass().getResourceAsStream("/files/" + name)) {
      Files.copy(in, target);
    }
    return target;
  }
}
