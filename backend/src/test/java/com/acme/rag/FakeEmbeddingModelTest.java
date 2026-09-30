package com.acme.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class FakeEmbeddingModelTest {

  private final FakeEmbeddingModel model = new FakeEmbeddingModel();

  @Test
  void isDeterministicAndNormalized() {
    float[] first = model.embed("Le télétravail est autorisé deux jours par semaine");
    float[] second = model.embed("Le télétravail est autorisé deux jours par semaine");

    assertThat(first).hasSize(FakeEmbeddingModel.DIMENSIONS).containsExactly(second);
    assertThat(cosine(first, first)).isCloseTo(1.0, within(1e-5));
  }

  @Test
  void textsSharingWordsAreSimilarUnrelatedTextsAreNot() {
    float[] policy = model.embed("Politique de télétravail : deux jours par semaine");
    float[] question = model.embed("Combien de jours de télétravail par semaine ?");
    float[] unrelated = model.embed("Quelle est la capitale du Pérou ?");

    assertThat(cosine(policy, question)).isGreaterThan(0.5);
    assertThat(cosine(policy, unrelated)).isLessThan(0.2);
  }

  @Test
  void ignoresCaseAndAccents() {
    assertThat(model.embed("PÉROU")).containsExactly(model.embed("perou"));
  }

  @Test
  void textWithoutWordsStillHasAUnitVector() {
    float[] vector = model.embed("123 !?");

    assertThat(cosine(vector, vector)).isCloseTo(1.0, within(1e-5));
  }

  @Test
  void embedsBatchesInOrder() {
    List<float[]> vectors = model.embed(List.of("alpha", "beta"));

    assertThat(vectors.get(0)).containsExactly(model.embed("alpha"));
    assertThat(vectors.get(1)).containsExactly(model.embed("beta"));
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
