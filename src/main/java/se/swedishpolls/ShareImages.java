package se.swedishpolls;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;
import se.swedishpolls.estimation.Coalitions;
import se.swedishpolls.estimation.EstimateHistory;
import se.swedishpolls.estimation.JointUncertainty;
import se.swedishpolls.estimation.NationalSeats;
import se.swedishpolls.source.PollCsv;

/**
 * Renders a publication's summary cards as PNG bytes. A card is a picture of one publication on one
 * day, so it carries that day rather than the day it is looked at, and a card for a closed coverage
 * period says so instead of reading as a current estimate.
 */
public final class ShareImages {
  private ShareImages() {}

  /** Changing anything a rendered pixel depends on requires a new version here. */
  public static final String RENDERER_VERSION = "1";

  public static final int WIDTH = 1200;
  public static final int HEIGHT = 630;
  public static final String MEDIA_TYPE = "image/png";

  public static final String OVERVIEW = "overview";
  public static final String PARTIES = "parties";
  public static final String SEATS = "seats";
  public static final String COALITIONS = "coalitions";

  /** Every rendered card kind. Polls, pollsters and method pages reuse the overview card. */
  public static final List<String> KINDS = kinds();

  private static final String FONT_FAMILY = "DejaVu Sans";
  private static final String SWEDISH_GLYPHS = "åäöÅÄÖ";

  private static final Color INK = new Color(0x14, 0x18, 0x1F);
  private static final Color MUTED = new Color(0x5A, 0x63, 0x72);
  private static final Color PAPER = new Color(0xFA, 0xFA, 0xF7);
  private static final Color RULE = new Color(0xD8, 0xDD, 0xE4);
  private static final Color BAR = new Color(0x2B, 0x4C, 0x7E);
  private static final Color RANGE = new Color(0x9A, 0xAF, 0xCC);

  /** One measured row of a card. */
  public record Bar(String label, double value, double lower, double upper, String annotation) {}

  /** One rendered card: what it says, what it draws and which day it belongs to. */
  public record Card(
      String kind,
      String language,
      String title,
      String subtitle,
      LocalDate fieldworkDate,
      List<Bar> bars,
      String footer,
      boolean historical) {
    public Card {
      bars = List.copyOf(bars);
    }

    @Override
    public List<Bar> bars() {
      return List.copyOf(bars);
    }
  }

  /**
   * Fails before any publication is staged when the runtime cannot draw Swedish text. A card with
   * missing glyphs is a broken publication, not a cosmetic problem.
   */
  public static void checkFonts() {
    final Font font = font(Font.BOLD, 32);
    if (!font.getFamily(Locale.ROOT).equals(FONT_FAMILY)) {
      throw new IllegalStateException(FONT_FAMILY + " is unavailable to Java2D");
    }
    if (font.canDisplayUpTo(SWEDISH_GLYPHS) != -1) {
      throw new IllegalStateException(FONT_FAMILY + " cannot display Swedish glyphs");
    }
    if (!ImageIO.getImageWritersByFormatName("png").hasNext()) {
      throw new IllegalStateException("No PNG writer is available");
    }
  }

  /** Every card of one publication, in both languages. */
  public static List<Card> cards(
      PublicationRun.Results results, ModelFreeze freeze, List<PollCsv.Poll> polls) {
    final List<Card> cards = new ArrayList<>();
    for (final String language : Translations.LANGUAGES) {
      final Translations text = Translations.of(language);
      final PublicationRun.Period headline = results.headline();
      final EstimateHistory.Day day = PublicationDocuments.lastDay(headline.history());
      final boolean historical = headline.period().effectiveTo() != null;
      final NationalSeats.SeatDraws drawn =
          NationalSeats.allocateDraws(headline.draws(), results.allocationRule());
      final NationalSeats.Summary seats = NationalSeats.summarize(drawn, results.intervalLevel());
      final Coalitions.Result coalitions = Coalitions.summarize(drawn, results.intervalLevel());

      final List<Bar> shares = new ArrayList<>();
      for (final String component : PublicationDocuments.displayComponents(headline.period())) {
        final EstimateHistory.Estimate estimate = day.components().get(component);
        if (estimate == null) {
          continue;
        }
        final JointUncertainty.Interval bounds =
            PublicationDocuments.bounds(estimate.intervals(), results.intervalLevel());
        shares.add(
            new Bar(
                text.component(component),
                freeze.resolution().quote(estimate.mean()),
                freeze.resolution().quote(bounds.lower()),
                freeze.resolution().quote(bounds.upper()),
                percent(freeze.resolution().quote(estimate.mean()), language)));
      }
      final List<Bar> ranked = new ArrayList<>(shares);
      ranked.sort((left, right) -> Double.compare(right.value(), left.value()));

      cards.add(
          new Card(
              OVERVIEW,
              language,
              text.text("card.overview"),
              text.text("site.name"),
              day.date(),
              ranked.subList(0, Math.min(6, ranked.size())),
              intervalFooter(text, results.intervalLevel()),
              historical));
      for (final String component : SiteRoutes.PARTIES) {
        cards.add(partyCard(results, freeze, polls, text, component));
      }
      cards.add(
          new Card(
              PARTIES,
              language,
              text.text("card.parties"),
              text.text("site.name"),
              day.date(),
              shares,
              intervalFooter(text, results.intervalLevel()),
              historical));

      final List<Bar> seatBars = new ArrayList<>();
      for (final NationalSeats.PartySeats party : seats.parties()) {
        seatBars.add(
            new Bar(
                text.component(party.component()),
                party.pointSeats(),
                party.lowerSeats(),
                party.upperSeats(),
                party.pointSeats() + " " + text.text("card.seatsOf")));
      }
      seatBars.sort((left, right) -> Double.compare(right.value(), left.value()));
      cards.add(
          new Card(
              SEATS,
              language,
              text.text("card.seats"),
              text.text("site.name"),
              day.date(),
              seatBars,
              intervalFooter(text, results.intervalLevel()),
              historical));

      final List<Bar> coalitionBars = new ArrayList<>();
      for (final String id : coalitions.overviewDefaults()) {
        final Coalitions.Seats entry = coalitions.coalition(id);
        coalitionBars.add(
            new Bar(
                text.coalition(id),
                entry.pointSeats(),
                entry.lowerSeats(),
                entry.upperSeats(),
                entry.pointSeats()
                    + " "
                    + text.text("card.seatsOf")
                    + " · "
                    + text.text("card.majority")
                    + " "
                    + probability(entry.majorityProbability(), language)));
      }
      cards.add(
          new Card(
              COALITIONS,
              language,
              text.text("card.coalitions"),
              text.text("site.name"),
              day.date(),
              coalitionBars,
              text.text("coalitions.note"),
              historical));
    }
    return List.copyOf(cards);
  }

  /** The immutable asset kind for one party page. */
  public static String partyKind(String component) {
    return "party-" + component.toLowerCase(Locale.ROOT);
  }

  private static Card partyCard(
      PublicationRun.Results results,
      ModelFreeze freeze,
      List<PollCsv.Poll> polls,
      Translations text,
      String component) {
    for (int index = results.periods().size() - 1; index >= 0; index--) {
      final PublicationRun.Period period = results.periods().get(index);
      final EstimateHistory.Day day = PublicationDocuments.lastDay(period.history());
      final EstimateHistory.Estimate estimate = day.components().get(component);
      if (estimate == null) {
        continue;
      }
      final JointUncertainty.Interval bounds =
          PublicationDocuments.bounds(estimate.intervals(), results.intervalLevel());
      final double mean = freeze.resolution().quote(estimate.mean());
      return new Card(
          partyKind(component),
          text.language(),
          text.component(component),
          text.text("site.name"),
          day.date(),
          List.of(
              new Bar(
                  text.component(component),
                  mean,
                  freeze.resolution().quote(bounds.lower()),
                  freeze.resolution().quote(bounds.upper()),
                  percent(mean, text.language()))),
          intervalFooter(text, results.intervalLevel()),
          period.period().effectiveTo() != null);
    }
    final Optional<LocalDate> lastObservation =
        polls.stream()
            .filter(poll -> poll.shares().get(component) != null)
            .map(poll -> poll.collectionTo() == null ? poll.collectionFrom() : poll.collectionTo())
            .filter(Objects::nonNull)
            .max(LocalDate::compareTo);
    return new Card(
        partyKind(component),
        text.language(),
        text.component(component),
        text.text("site.name"),
        lastObservation.orElse(results.lastFieldworkDate()),
        List.of(),
        text.text("card.unavailable"),
        true);
  }

  private static List<String> kinds() {
    final List<String> kinds = new ArrayList<>(List.of(OVERVIEW, PARTIES, SEATS, COALITIONS));
    SiteRoutes.PARTIES.stream().map(ShareImages::partyKind).forEach(kinds::add);
    return List.copyOf(kinds);
  }

  /** The card as PNG bytes, decoded once before it is returned so a broken encoder cannot ship. */
  public static byte[] render(Card card, Translations text) {
    final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    final Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setColor(PAPER);
      graphics.fillRect(0, 0, WIDTH, HEIGHT);

      graphics.setColor(MUTED);
      graphics.setFont(font(Font.PLAIN, 22));
      graphics.drawString(card.subtitle(), 64, 72);

      graphics.setColor(INK);
      graphics.setFont(font(Font.BOLD, 46));
      graphics.drawString(card.title(), 64, 130);

      graphics.setColor(MUTED);
      graphics.setFont(font(Font.PLAIN, 22));
      graphics.drawString(text.text("card.asOf") + " " + card.fieldworkDate(), 64, 168);
      if (card.historical()) {
        graphics.setColor(new Color(0x8A, 0x4B, 0x08));
        graphics.setFont(font(Font.BOLD, 22));
        graphics.drawString(text.text("card.historical"), 64, 200);
      }

      graphics.setColor(RULE);
      graphics.setStroke(new BasicStroke(2));
      graphics.drawLine(64, 220, WIDTH - 64, 220);

      drawBars(graphics, card);

      graphics.setColor(MUTED);
      graphics.setFont(font(Font.PLAIN, 18));
      graphics.drawString(clip(graphics, card.footer(), WIDTH - 128), 64, HEIGHT - 40);
    } finally {
      graphics.dispose();
    }
    return encode(image);
  }

  private static void drawBars(Graphics2D graphics, Card card) {
    if (card.bars().isEmpty()) {
      return;
    }
    final int top = 256;
    final int bottom = HEIGHT - 72;
    final int rows = card.bars().size();
    final int rowHeight = Math.max(24, (bottom - top) / rows);
    final int labelWidth = 260;
    final int trackLeft = 64 + labelWidth;
    final int trackWidth = WIDTH - 64 - trackLeft - 260;
    double largest = 0;
    for (final Bar bar : card.bars()) {
      largest = Math.max(largest, Math.max(bar.value(), bar.upper()));
    }
    if (largest <= 0) {
      largest = 1;
    }
    for (int row = 0; row < rows; row++) {
      final Bar bar = card.bars().get(row);
      final int centre = top + row * rowHeight + rowHeight / 2;
      graphics.setColor(INK);
      graphics.setFont(font(Font.PLAIN, Math.min(24, rowHeight - 6)));
      graphics.drawString(clip(graphics, bar.label(), labelWidth - 16), 64, centre + 8);

      final int barHeight = Math.max(8, Math.min(22, rowHeight - 14));
      final int lower = (int) Math.round(trackWidth * bar.lower() / largest);
      final int upper = (int) Math.round(trackWidth * bar.upper() / largest);
      graphics.setColor(RANGE);
      graphics.fillRect(trackLeft + lower, centre - 3, Math.max(2, upper - lower), 6);
      graphics.setColor(BAR);
      graphics.fillRect(
          trackLeft,
          centre - barHeight / 2,
          Math.max(2, (int) Math.round(trackWidth * bar.value() / largest)),
          barHeight);

      graphics.setColor(INK);
      graphics.setFont(font(Font.BOLD, Math.min(22, rowHeight - 8)));
      graphics.drawString(
          clip(graphics, bar.annotation(), 252), trackLeft + trackWidth + 16, centre + 8);
    }
  }

  private static byte[] encode(BufferedImage image) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      if (!ImageIO.write(image, "png", out)) {
        throw new IllegalStateException("No PNG writer is available");
      }
      final byte[] bytes = out.toByteArray();
      final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
      if (decoded == null || decoded.getWidth() != WIDTH || decoded.getHeight() != HEIGHT) {
        throw new IllegalStateException("The rendered card could not be decoded");
      }
      return bytes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String clip(Graphics2D graphics, String text, int width) {
    if (graphics.getFontMetrics().stringWidth(text) <= width) {
      return text;
    }
    final StringBuilder shortened = new StringBuilder(text);
    while (shortened.length() > 1
        && graphics.getFontMetrics().stringWidth(shortened + "…") > width) {
      shortened.setLength(shortened.length() - 1);
    }
    return shortened + "…";
  }

  private static String intervalFooter(Translations text, double level) {
    return text.text("card.interval").replace("{level}", Long.toString(Math.round(level * 100)));
  }

  private static String percent(double value, String language) {
    return String.format(locale(language), "%.1f%%", value);
  }

  /** A probability never reads as 0% or 100%; the display says less than or greater than. */
  private static String probability(double value, String language) {
    if (value < 0.01) {
      return "<1%";
    }
    if (value > 0.99) {
      return ">99%";
    }
    return String.format(locale(language), "%.0f%%", value * 100);
  }

  private static Locale locale(String language) {
    return Translations.SWEDISH.equals(language) ? Locale.forLanguageTag("sv") : Locale.ROOT;
  }

  private static Font font(int style, int size) {
    return new Font(FONT_FAMILY, style, size);
  }
}
