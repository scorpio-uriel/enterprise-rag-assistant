package com.acme.rag;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Embedding déterministe, sans Ollama : chaque mot (minuscules, sans accents) est haché vers une
 * des 768 dimensions, qu'il incrémente, puis le vecteur est normalisé. Deux textes qui partagent
 * des mots sont donc proches (cosinus élevé), deux textes sans mot commun ont un cosinus proche de
 * 0.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

  public static final int DIMENSIONS = 768;

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<String> texts = request.getInstructions();
    return new EmbeddingResponse(
        IntStream.range(0, texts.size())
            .mapToObj(i -> new Embedding(vectorize(texts.get(i)), i))
            .toList());
  }

  @Override
  public float[] embed(Document document) {
    return vectorize(document.getText());
  }

  @Override
  public int dimensions() {
    return DIMENSIONS; // évite l'appel d'embedding de sonde fait par défaut
  }

  static float[] vectorize(String text) {
    float[] vector = new float[DIMENSIONS];
    String normalized =
        Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "") // retire les accents : « Pérou » == « perou »
            .toLowerCase(Locale.ROOT);
    for (String word : normalized.split("\\P{L}+")) {
      if (!word.isEmpty()) {
        vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
      }
    }
    double norm = 0;
    for (float v : vector) {
      norm += v * v;
    }
    if (norm == 0) {
      vector[0] = 1; // texte sans mot : un vecteur nul rendrait le cosinus indéfini
      return vector;
    }
    float inverse = (float) (1 / Math.sqrt(norm));
    for (int i = 0; i < DIMENSIONS; i++) {
      vector[i] *= inverse;
    }
    return vector;
  }
}
