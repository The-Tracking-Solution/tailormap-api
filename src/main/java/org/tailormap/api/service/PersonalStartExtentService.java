/*
 * Copyright (C) 2026 The Tracking Solution.
 * SPDX-License-Identifier: MIT
 */
package org.tailormap.api.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.repository.UserRepository;
import org.tailormap.api.viewer.model.PersonalStartExtent;

/** Administrator-controlled local account start positions, separate from legacy V39 extents. */
@Service
public class PersonalStartExtentService {
  private final JdbcClient jdbc;
  private final UserRepository users;

  public PersonalStartExtentService(JdbcClient jdbc, UserRepository users) {
    this.jdbc = jdbc;
    this.users = users;
  }

  public PersonalStartExtent get(Application application, Authentication authentication) {
    String username = localUsername(authentication);
    if (!isEnabled(application)) {
      return new PersonalStartExtent().crs(application.getCrs()).center(null).extent(null).mayEdit(false);
    }
    requireUser(username);
    return getForUser(application, username);
  }

  public PersonalStartExtent getForUser(Application application, String username) {
    requireUser(username);
    return jdbc.sql("""
SELECT crs, center_x, center_y, zoom, may_edit, extent_minx, extent_miny, extent_maxx, extent_maxy FROM tts_start.user_start_position
WHERE application_id = :application AND username = :username
""")
        .param("application", application.getId())
        .param("username", username)
        .query((rs, row) -> {
          PersonalStartExtent result = new PersonalStartExtent().crs(application.getCrs()).center(null).extent(null).mayEdit(rs.getBoolean("may_edit"));
          if (application.getCrs().equals(rs.getString("crs")) && rs.getObject("zoom") != null) {
            result.center(List.of(rs.getDouble("center_x"), rs.getDouble("center_y"))).zoom(rs.getDouble("zoom"));
          }
          if (application.getCrs().equals(rs.getString("crs")) && rs.getObject("extent_minx") != null && rs.getObject("zoom") == null) {
            result.extent(List.of(rs.getDouble("extent_minx"), rs.getDouble("extent_miny"),
                rs.getDouble("extent_maxx"), rs.getDouble("extent_maxy")));
          }
          return result;
        })
        .optional()
        .orElseGet(() -> new PersonalStartExtent().crs(application.getCrs()).center(null).extent(null).mayEdit(false));
  }

  public void save(Application application, Authentication authentication, PersonalStartExtent position) {
    String username = localUsername(authentication);
    requireEnabled(application);
    requireUser(username);
    validate(application, position, false);
    // Permission is checked in the mutation, so a concurrent admin revocation cannot be bypassed.
    int changed = jdbc.sql("""
UPDATE tts_start.user_start_position SET crs=:crs, center_x=:x, center_y=:y, zoom=:zoom,
extent_minx=:minx, extent_miny=:miny, extent_maxx=:maxx, extent_maxy=:maxy
WHERE application_id=:application AND username=:username AND may_edit=true
""")
        .param("application", application.getId()).param("username", username)
        .param("crs", position.getCrs()).param("x", coordinate(position.getCenter(), 0))
        .param("y", coordinate(position.getCenter(), 1)).param("zoom", position.getZoom())
        .param("minx", coordinate(position.getExtent(), 0)).param("miny", coordinate(position.getExtent(), 1))
        .param("maxx", coordinate(position.getExtent(), 2)).param("maxy", coordinate(position.getExtent(), 3)).update();
    requireEditable(changed);
  }

  public void delete(Application application, Authentication authentication) {
    String username = localUsername(authentication);
    requireEnabled(application);
    requireUser(username);
    int changed = jdbc.sql("""
UPDATE tts_start.user_start_position SET center_x=NULL, center_y=NULL, zoom=NULL,
extent_minx=NULL, extent_miny=NULL, extent_maxx=NULL, extent_maxy=NULL
WHERE application_id=:application AND username=:username AND may_edit=true
""")
        .param("application", application.getId()).param("username", username).update();
    requireEditable(changed);
  }

  public void saveForUser(Application application, String username, PersonalStartExtent position) {
    requireUser(username);
    validate(application, position, true);
    List<Double> center = position.getCenter();
    jdbc.sql("""
INSERT INTO tts_start.user_start_position (application_id, username, crs, center_x, center_y, zoom, may_edit, extent_minx, extent_miny, extent_maxx, extent_maxy)
VALUES (:application, :username, :crs, :x, :y, :zoom, :edit, :minx, :miny, :maxx, :maxy)
ON CONFLICT (application_id, username) DO UPDATE SET
crs=EXCLUDED.crs, center_x=EXCLUDED.center_x, center_y=EXCLUDED.center_y,
zoom=EXCLUDED.zoom, may_edit=EXCLUDED.may_edit,
extent_minx=EXCLUDED.extent_minx, extent_miny=EXCLUDED.extent_miny,
extent_maxx=EXCLUDED.extent_maxx, extent_maxy=EXCLUDED.extent_maxy
""")
        .param("application", application.getId()).param("username", username)
        .param("crs", position.getCrs()).param("x", center == null ? null : center.get(0))
        .param("y", center == null ? null : center.get(1)).param("zoom", position.getZoom())
        .param("edit", Boolean.TRUE.equals(position.getMayEdit()))
        .param("minx", coordinate(position.getExtent(), 0)).param("miny", coordinate(position.getExtent(), 1))
        .param("maxx", coordinate(position.getExtent(), 2)).param("maxy", coordinate(position.getExtent(), 3)).update();
  }

  private static Double coordinate(List<Double> values, int index) {
    return values == null ? null : values.get(index);
  }

  private void requireUser(String username) {
    if (users.findById(username).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local user not found");
    }
  }

  private static boolean isEnabled(Application application) {
    return application.getSettings() != null && application.getSettings().getUiSettings() != null
        && Boolean.TRUE.equals(application.getSettings().getUiSettings().getPersonalStartPositionEnabled());
  }

  private static void requireEnabled(Application application) {
    if (!isEnabled(application)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Personal start positions disabled");
    }
  }

  private static void requireEditable(int changed) {
    if (changed != 1) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator may change this start position");
    }
  }

  static void validate(Application application, PersonalStartExtent position, boolean allowEmpty) {
    if (position == null || !application.getCrs().equals(position.getCrs())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Position must use the application CRS");
    }
    List<Double> center = position.getCenter();
    Double zoom = position.getZoom();
    List<Double> extent = position.getExtent();
    if (extent != null) {
      if (center != null || zoom != null || extent.size() != 4
          || extent.stream().anyMatch(v -> v == null || !Double.isFinite(v))
          || extent.get(0) >= extent.get(2) || extent.get(1) >= extent.get(3)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid rectangle or mixed position modes");
      }
      return;
    }
    if (allowEmpty && center == null && zoom == null) {
      return;
    }
    if (center == null || center.size() != 2
        || center.stream().anyMatch(v -> v == null || !Double.isFinite(v))
        || zoom == null || !Double.isFinite(zoom) || zoom < 0 || zoom > 28) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A finite center and zoom between 0 and 28 are required");
    }
  }

  static String localUsername(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    if (authentication.getPrincipal() instanceof OidcUser) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Personal start positions currently support local accounts only");
    }
    return authentication.getName();
  }
}
