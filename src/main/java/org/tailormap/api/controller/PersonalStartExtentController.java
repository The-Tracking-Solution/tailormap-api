/*
 * Copyright (C) 2026 The Tracking Solution.
 *
 * SPDX-License-Identifier: MIT
 */
package org.tailormap.api.controller;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.tailormap.api.annotation.AppRestController;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.service.PersonalStartExtentService;
import org.tailormap.api.viewer.model.PersonalStartExtent;

/** AppRestControllerAdvice resolves the application and enforces application access. */
@AppRestController
public class PersonalStartExtentController {
  private final PersonalStartExtentService service;

  public PersonalStartExtentController(PersonalStartExtentService service) {
    this.service = service;
  }

  @GetMapping("${tailormap-api.base-path}/app/{viewerName}/user/start-extent")
  public ResponseEntity<PersonalStartExtent> get(
      @ModelAttribute Application application, Authentication authentication) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(application, authentication));
  }

  @PutMapping("${tailormap-api.base-path}/app/{viewerName}/user/start-extent")
  public ResponseEntity<Void> save(
      @ModelAttribute Application application,
      Authentication authentication,
      @Valid @RequestBody PersonalStartExtent extent) {
    service.save(application, authentication, extent);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("${tailormap-api.base-path}/app/{viewerName}/user/start-extent")
  public ResponseEntity<Void> delete(@ModelAttribute Application application, Authentication authentication) {
    service.delete(application, authentication);
    return ResponseEntity.noContent().build();
  }
}
