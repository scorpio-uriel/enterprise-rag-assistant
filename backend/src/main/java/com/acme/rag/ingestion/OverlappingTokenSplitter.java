package com.acme.rag.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.transformer.splitter.TextSplitter;

/**
 * Découpe un texte en fenêtres de {@code chunkSize} tokens qui se chevauchent de {@code overlap}
 * tokens : une phrase coupée en fin de chunk est reprise au début du suivant, sans perdre son
 * contexte. Le {@code TokenTextSplitter} de Spring AI 2.0 ne propose pas de chevauchement.
 *
 * <p>La classe parente {@link TextSplitter} recopie les métadonnées (dont {@code page}) du document
 * source sur chacun de ses chunks.
 */
public class OverlappingTokenSplitter extends TextSplitter {

  // Même encodage que TokenTextSplitter : une approximation suffisante des tokens de nomic-embed
  private static final Encoding ENCODING =
      Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

  private final int chunkSize;
  private final int overlap;

  public OverlappingTokenSplitter(int chunkSize, int overlap) {
    if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
      throw new IllegalArgumentException(
          "Il faut 0 <= overlap < chunkSize (reçu " + overlap + " / " + chunkSize + ")");
    }
    this.chunkSize = chunkSize;
    this.overlap = overlap;
  }

  @Override
  protected List<String> splitText(String text) {
    IntArrayList tokens = ENCODING.encode(text);
    List<String> chunks = new ArrayList<>();
    int step = chunkSize - overlap;
    for (int start = 0; start < tokens.size(); start += step) {
      int end = Math.min(start + chunkSize, tokens.size());
      String chunk = ENCODING.decode(slice(tokens, start, end)).strip();
      if (!chunk.isEmpty()) {
        chunks.add(chunk);
      }
      if (end == tokens.size()) {
        break; // la dernière fenêtre atteint la fin : pas de chunk fait uniquement de chevauchement
      }
    }
    return chunks;
  }

  private static IntArrayList slice(IntArrayList tokens, int start, int end) {
    IntArrayList slice = new IntArrayList(end - start);
    for (int i = start; i < end; i++) {
      slice.add(tokens.get(i));
    }
    return slice;
  }
}
