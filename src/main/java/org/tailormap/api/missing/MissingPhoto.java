package org.tailormap.api.missing;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Decode/repaint/encode removes EXIF, filenames and auxiliary metadata; never persist the upload. */
public final class MissingPhoto {
  private MissingPhoto() {}

  public static byte[] sanitize(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > 10000000) throw invalid();
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw invalid();
      ImageReader reader = readers.next();
      try {
        if (!Set.of("JPEG", "PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT))) throw invalid();
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > 16000000) throw invalid();
        BufferedImage original = reader.read(0);
        double ratio = Math.min(1.0, 1600.0 / Math.max(width, height));
        BufferedImage clean = new BufferedImage(
            Math.max(1, (int) (width * ratio)),
            Math.max(1, (int) (height * ratio)),
            BufferedImage.TYPE_INT_RGB);
        var graphics = clean.createGraphics();
        try {
          graphics.setColor(Color.WHITE);
          graphics.fillRect(0, 0, clean.getWidth(), clean.getHeight());
          graphics.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          graphics.drawImage(original, 0, 0, clean.getWidth(), clean.getHeight(), null);
        } finally {
          graphics.dispose();
        }
        var result = new ByteArrayOutputStream();
        if (!ImageIO.write(clean, "jpeg", result) || result.size() > 5000000) throw invalid();
        return result.toByteArray();
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "Use a JPEG/PNG photo up to 10 MB and 16 megapixels");
  }
}
