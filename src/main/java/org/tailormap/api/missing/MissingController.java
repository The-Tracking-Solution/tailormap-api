package org.tailormap.api.missing;

import static org.tailormap.api.missing.MissingModels.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tailormap.api.annotation.AppRestController;
import org.tailormap.api.persistence.Application;

@AppRestController
@ConditionalOnProperty(name = "tailormap.missing.enabled", havingValue = "true")
@RequestMapping("${tailormap-api.base-path}/app/{viewerName}/missing-persons")
public class MissingController {
  private final MissingService service;

  public MissingController(MissingService service) {
    this.service = service;
  }

  private static <T> ResponseEntity<T> response(T body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(body);
  }

  @GetMapping("/configuration")
  public ResponseEntity<Map<String, Object>> configuration(@ModelAttribute Application app, Authentication auth) {
    return response(service.configuration(app, auth));
  }

  @GetMapping("/cases")
  public ResponseEntity<List<Map<String, Object>>> list(@ModelAttribute Application app, Authentication auth) {
    return response(service.list(app, auth));
  }

  @GetMapping("/statistics")
  public ResponseEntity<List<Map<String, Object>>> stats(@ModelAttribute Application app, Authentication auth) {
    return response(service.statistics(app, auth));
  }

  @PostMapping("/cases")
  public ResponseEntity<Map<String, Object>> create(
      @ModelAttribute Application app, Authentication auth, @Valid @RequestBody CaseInput input) {
    return response(service.create(app, auth, input));
  }

  @GetMapping("/cases/{id}")
  public ResponseEntity<Map<String, Object>> get(
      @ModelAttribute Application app, Authentication auth, @PathVariable UUID id) {
    return response(service.get(app, auth, id));
  }

  @PutMapping("/cases/{id}")
  public ResponseEntity<Map<String, Object>> update(
      @ModelAttribute Application app,
      Authentication auth,
      @PathVariable UUID id,
      @Valid @RequestBody CaseInput input) {
    return response(service.update(app, auth, id, input));
  }

  @PostMapping("/cases/{id}/events")
  public ResponseEntity<Void> event(
      @ModelAttribute Application app,
      Authentication auth,
      @PathVariable UUID id,
      @Valid @RequestBody EventInput input) {
    service.addEvent(app, auth, id, input);
    return response(null);
  }

  @GetMapping("/cases/{id}/search-area")
  public ResponseEntity<Map<String, Object>> area(
      @ModelAttribute Application app, Authentication auth, @PathVariable UUID id) {
    return response(service.searchArea(app, auth, id));
  }

  @PostMapping(value = "/cases/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> upload(
      @ModelAttribute Application app,
      Authentication auth,
      @PathVariable UUID id,
      @RequestParam long version,
      @RequestPart("photo") MultipartFile photo)
      throws IOException {
    if (photo.getSize() > 10000000) return ResponseEntity.status(413).build();
    service.photo(app, auth, id, version, photo.getBytes());
    return response(null);
  }

  @GetMapping("/cases/{id}/photo")
  public ResponseEntity<byte[]> photo(@ModelAttribute Application app, Authentication auth, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "inline; filename=photo.jpg")
        .contentType(MediaType.IMAGE_JPEG)
        .body(service.photo(app, auth, id));
  }

  @PostMapping("/cases/{id}/close")
  public ResponseEntity<Map<String, Object>> close(
      @ModelAttribute Application app,
      Authentication auth,
      @PathVariable UUID id,
      @Valid @RequestBody CloseInput input) {
    return response(service.close(app, auth, id, input));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<Map<String, String>> databaseError() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .cacheControl(CacheControl.noStore())
        .body(Map.of("message", "The case could not be saved. Reload and retry."));
  }
}
