package com.acme.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.rag.common.RagProperties;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/** Test unitaire pur : pas de contexte Spring, le validateur est instancié à la main. */
class FileValidatorTest {

  private final FileValidator validator =
      new FileValidator(new RagProperties("unused", DataSize.ofMegabytes(20)));

  @ParameterizedTest
  @CsvSource({
    "sample.pdf, pdf, application/pdf",
    "sample.docx, docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "sample.md, md, text/markdown",
    "sample.txt, txt, text/plain"
  })
  void acceptsTheFourSupportedFormats(String resource, String extension, String type)
      throws IOException { // AC2.2
    ValidatedFile validated = validator.validate(fixture(resource, resource));

    assertThat(validated).isEqualTo(new ValidatedFile(extension, type));
  }

  @Test
  void acceptsUppercaseExtension() throws IOException {
    assertThat(validator.validate(fixture("sample.pdf", "RAPPORT.PDF")).extension())
        .isEqualTo("pdf");
  }

  @Test
  void rejectsExecutable() throws IOException { // AC2.4
    assertThatThrownBy(() -> validator.validate(fixture("fake.exe", "fake.exe")))
        .isInstanceOf(InvalidFileException.class)
        .hasMessageContaining("Extension non autorisée");
  }

  @Test
  void rejectsExecutableRenamedToPdf() throws IOException { // AC2.4
    assertThatThrownBy(() -> validator.validate(fixture("fake-exe.pdf", "fake-exe.pdf")))
        .isInstanceOf(InvalidFileException.class)
        .hasMessageContaining("ne correspond pas à l'extension .pdf");
  }

  @Test
  void rejectsExecutableRenamedToTxt() throws IOException {
    assertThatThrownBy(() -> validator.validate(fixture("fake.exe", "notes.txt")))
        .isInstanceOf(InvalidFileException.class);
  }

  @Test
  void rejectsPdfRenamedToDocx() throws IOException {
    assertThatThrownBy(() -> validator.validate(fixture("sample.pdf", "sample.docx")))
        .isInstanceOf(InvalidFileException.class);
  }

  @Test
  void rejectsMissingExtension() {
    MockMultipartFile file = new MockMultipartFile("file", "README", null, "texte".getBytes());

    assertThatThrownBy(() -> validator.validate(file)).isInstanceOf(InvalidFileException.class);
  }

  @Test
  void rejectsEmptyFile() {
    MockMultipartFile file = new MockMultipartFile("file", "vide.txt", null, new byte[0]);

    assertThatThrownBy(() -> validator.validate(file))
        .isInstanceOf(InvalidFileException.class)
        .hasMessage("Le fichier est vide");
  }

  @Test
  void rejectsFileOverMaxSize() { // AC2.3 (défense en profondeur)
    FileValidator small = new FileValidator(new RagProperties("unused", DataSize.ofBytes(10)));
    MockMultipartFile file =
        new MockMultipartFile("file", "gros.txt", null, "plus de dix octets".getBytes());

    assertThatThrownBy(() -> small.validate(file))
        .isInstanceOf(InvalidFileException.class)
        .hasMessageStartingWith("Le fichier dépasse la taille maximale");
  }

  static MockMultipartFile fixture(String resource, String fileName) throws IOException {
    try (InputStream in = FileValidatorTest.class.getResourceAsStream("/files/" + resource)) {
      return new MockMultipartFile("file", fileName, "application/octet-stream", in);
    }
  }
}
