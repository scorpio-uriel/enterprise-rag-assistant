package com.acme.rag.ingestion;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Choisit le lecteur Spring AI selon le type MIME détecté à l'import, et ne garde que le texte et
 * la page. Les métadonnées propres à chaque lecteur (nom du fichier sur disque…) sont écartées :
 * {@code IngestionService} ajoute ensuite {@code documentId} et {@code fileName}.
 */
@Component
public class DocumentReaderFactory {

  static final String PAGE = "page";

  private static final String PDF = "application/pdf";
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  /** Un {@link Document} Spring AI par page pour un PDF, un seul pour les autres formats. */
  public List<Document> read(Path file, String contentType) {
    Resource resource = new FileSystemResource(file);
    if (PDF.equals(contentType)) {
      return readPdf(resource);
    }
    DocumentReader reader;
    if (DOCX.equals(contentType)) {
      reader = new TikaDocumentReader(resource);
    } else if (contentType.startsWith("text/")) {
      reader = new TextReader(resource); // UTF-8 par défaut
    } else {
      throw new IllegalArgumentException("Type de document non pris en charge : " + contentType);
    }
    return reader.get().stream()
        .filter(DocumentReaderFactory::hasText)
        .map(d -> text(d, Map.of()))
        .toList();
  }

  private static List<Document> readPdf(Resource resource) {
    PdfDocumentReaderConfig config =
        PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build();
    return new PagePdfDocumentReader(resource, config)
        .get().stream()
            .filter(DocumentReaderFactory::hasText)
            .map(
                page ->
                    text(
                        page,
                        Map.of(
                            PAGE,
                            page.getMetadata()
                                .get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER))))
            .toList();
  }

  private static boolean hasText(Document document) {
    return document.getText() != null && !document.getText().isBlank();
  }

  private static Document text(Document source, Map<String, Object> metadata) {
    return new Document(source.getText(), metadata);
  }
}
