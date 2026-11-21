/*
 * Copyright (C) 2026 The Tracking Solution.
 * SPDX-License-Identifier: MIT
 */
package org.tailormap.api.controller.admin;

import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.repository.ApplicationRepository;
import org.tailormap.api.security.AuthorisationService;
import org.tailormap.api.security.TailormapUserDetailsService;
import org.tailormap.api.security.annotation.PreAuthorizeAdmin;
import org.tailormap.api.service.PersonalStartExtentService;
import org.tailormap.api.viewer.model.PersonalStartExtent;

@RestController
@PreAuthorizeAdmin
@RequestMapping("${tailormap-api.admin.base-path}")
public class PersonalStartPositionAdminController {
  private final ApplicationRepository applications;
  private final PersonalStartExtentService positions;
  private final TailormapUserDetailsService users;
  private final AuthorisationService access;

  public PersonalStartPositionAdminController(
      ApplicationRepository applications,
      PersonalStartExtentService positions,
      TailormapUserDetailsService users,
      AuthorisationService access) {
    this.applications = applications;
    this.positions = positions;
    this.users = users;
    this.access = access;
  }

  private Application application(Long id) {
    return applications.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  public record StartApplication(String id, String name, String title, String crs, boolean enabled) {}

  @GetMapping("/users/{username}/start-position-applications")
  public ResponseEntity<List<StartApplication>> applications(@PathVariable String username) {
    UserDetails user = localUser(username);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(applications.findAll().stream()
            .filter(app -> access.userAllowedToViewApplication(app, user))
            .map(app -> new StartApplication(
                app.getId().toString(),
                app.getName(),
                app.getTitle(),
                app.getCrs(),
                app.getSettings() != null
                    && app.getSettings().getUiSettings() != null
                    && Boolean.TRUE.equals(app.getSettings()
                        .getUiSettings()
                        .getPersonalStartPositionEnabled())))
            .sorted(Comparator.comparing(StartApplication::name))
            .toList());
  }

  private UserDetails localUser(String username) {
    try {
      return users.loadUserByUsername(username);
    } catch (UsernameNotFoundException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local user not found");
    }
  }

  private Application accessibleApplication(Long id, String username) {
    Application app = application(id);
    if (!access.userAllowedToViewApplication(app, localUser(username))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This user has no access to this application");
    }
    return app;
  }

  @GetMapping("/applications/{applicationId}/user-start-position/{username}")
  public ResponseEntity<PersonalStartExtent> get(@PathVariable Long applicationId, @PathVariable String username) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(positions.getForUser(accessibleApplication(applicationId, username), username));
  }

  @PutMapping("/applications/{applicationId}/user-start-position/{username}")
  public ResponseEntity<Void> save(
      @PathVariable Long applicationId,
      @PathVariable String username,
      @Valid @RequestBody PersonalStartExtent position) {
    positions.saveForUser(accessibleApplication(applicationId, username), username, position);
    return ResponseEntity.noContent().build();
  }
}
