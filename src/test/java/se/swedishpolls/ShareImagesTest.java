package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ShareImagesTest {
  private static final LocalDate FIELDWORK = LocalDate.of(2026, 9, 5);

  private static ShareImages.Card card(String kind, String language, boolean historical) {
    final Translations text = Translations.of(language);
    return new ShareImages.Card(
        kind,
        language,
        text.text("card." + kind),
        text.text("site.name"),
        FIELDWORK,
        List.of(
            new ShareImages.Bar(text.component("S"), 27.0, 25.6, 28.4, "27,0 %"),
            new ShareImages.Bar(text.component("SD"), 19.7, 18.4, 21.0, "19,7 %"),
            new ShareImages.Bar(text.component("MP"), 7.8, 6.9, 8.7, "7,8 %")),
        text.text("coalitions.note"),
        historical);
  }

  @Test
  void theRuntimeCanDrawSwedishTextBeforeAnyPublicationIsStaged() {
    assertDoesNotThrow(ShareImages::checkFonts);
  }

  @Test
  void everyCardRendersAtTheApprovedSizeInBothLanguages() throws Exception {
    for (final String kind : ShareImages.KINDS) {
      for (final String language : Translations.LANGUAGES) {
        final byte[] png =
            ShareImages.render(card(kind, language, false), Translations.of(language));
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded, kind + "/" + language + " did not decode");
        assertEquals(1200, decoded.getWidth());
        assertEquals(630, decoded.getHeight());
        assertTrue(ink(decoded) > 1000, kind + "/" + language + " drew almost nothing");
      }
    }
  }

  @Test
  void theTwoLanguagesAreTwoDifferentPictures() {
    assertFalse(
        java.util.Arrays.equals(
            ShareImages.render(card(ShareImages.OVERVIEW, "sv", false), Translations.of("sv")),
            ShareImages.render(card(ShareImages.OVERVIEW, "en", false), Translations.of("en"))));
  }

  @Test
  void aCardOfAClosedCoveragePeriodSaysItIsHistorical() {
    final byte[] current =
        ShareImages.render(card(ShareImages.PARTIES, "sv", false), Translations.of("sv"));
    final byte[] historical =
        ShareImages.render(card(ShareImages.PARTIES, "sv", true), Translations.of("sv"));
    assertFalse(
        java.util.Arrays.equals(current, historical),
        "A historical card must not look like a current estimate");
  }

  @Test
  void renderingIsDeterministicSoRepublishingTheSameCardChangesNoByte() {
    assertArrayEquals(
        ShareImages.render(card(ShareImages.SEATS, "en", false), Translations.of("en")),
        ShareImages.render(card(ShareImages.SEATS, "en", false), Translations.of("en")));
  }

  private static long ink(BufferedImage image) {
    long drawn = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) != new java.awt.Color(0xFA, 0xFA, 0xF7).getRGB()) {
          drawn++;
        }
      }
    }
    return drawn;
  }
}
