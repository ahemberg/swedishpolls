package se.swedishpolls;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

final class ImageSmokeCheck {
  private static final String TEXT = "Svenska väljare: åäö ÅÄÖ";

  private ImageSmokeCheck() {}

  public static void main(String[] args) throws IOException {
    if (!GraphicsEnvironment.isHeadless()) {
      throw new IllegalStateException("Java2D must run headless");
    }

    var font = new Font("DejaVu Sans", Font.BOLD, 32);
    if (!font.getFamily(Locale.ROOT).equals("DejaVu Sans") || font.canDisplayUpTo(TEXT) != -1) {
      throw new IllegalStateException("DejaVu Sans with Swedish glyphs is unavailable");
    }

    var image = new BufferedImage(640, 120, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(font);
      graphics.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.drawString(TEXT, 24, 72);
    } finally {
      graphics.dispose();
    }

    Path output = Path.of(args.length == 0 ? "/tmp/swedishpolls-smoke.png" : args[0]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    if (!ImageIO.write(image, "png", output.toFile())) {
      throw new IllegalStateException("No PNG writer is available");
    }
    var decoded = ImageIO.read(output.toFile());
    if (decoded == null || decoded.getWidth() != 640 || decoded.getHeight() != 120) {
      throw new IllegalStateException("Rendered PNG could not be decoded");
    }
    boolean hasInk = false;
    for (int y = 0; y < decoded.getHeight() && !hasInk; y++) {
      for (int x = 0; x < decoded.getWidth(); x++) {
        if (decoded.getRGB(x, y) != Color.WHITE.getRGB()) {
          hasInk = true;
          break;
        }
      }
    }
    if (!hasInk) {
      throw new IllegalStateException("Rendered PNG contains no text");
    }
    System.out.printf("Rendered %s with %s%n", output, font.getFamily(Locale.ROOT));
  }
}
