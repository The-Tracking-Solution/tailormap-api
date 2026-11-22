package org.tailormap.api.missing;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MissingPhotoTest {
  @Test
  void rejectsUntrustedOrUnsupportedUploads() {
    assertThrows(
        ResponseStatusException.class,
        () -> MissingPhoto.sanitize("<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8)));
    assertThrows(ResponseStatusException.class, () -> MissingPhoto.sanitize(new byte[10000001]));
    assertThrows(ResponseStatusException.class, () -> MissingPhoto.sanitize(new byte[0]));
  }

  @Test
  void stripsTrailingMetadataAndResizesToJpeg() throws Exception {
    var source = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_ARGB);
    var upload = new ByteArrayOutputStream();
    ImageIO.write(source, "png", upload);
    upload.write("PRIVATE-EXIF-GPS-NAME".getBytes(StandardCharsets.UTF_8));
    byte[] clean = MissingPhoto.sanitize(upload.toByteArray());
    assertEquals(0xff, clean[0] & 0xff);
    assertFalse(new String(clean, StandardCharsets.ISO_8859_1).contains("PRIVATE-EXIF"));
    var decoded = ImageIO.read(new ByteArrayInputStream(clean));
    assertEquals(1600, decoded.getWidth());
    assertEquals(800, decoded.getHeight());
  }
}
