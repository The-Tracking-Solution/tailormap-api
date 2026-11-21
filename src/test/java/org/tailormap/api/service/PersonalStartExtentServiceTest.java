/*
 * Copyright (C) 2026 The Tracking Solution.
 * SPDX-License-Identifier: MIT
 */
package org.tailormap.api.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.persistence.User;
import org.tailormap.api.persistence.json.AppUiSettings;
import org.tailormap.api.repository.UserRepository;
import org.tailormap.api.viewer.model.PersonalStartExtent;

class PersonalStartExtentServiceTest {
  private final Application application = new Application().setId(7L).setCrs("EPSG:3857");
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final UserRepository users = mock(UserRepository.class);
  private final PersonalStartExtentService service = new PersonalStartExtentService(jdbc, users);

  @Test
  void acceptsCenterAndFractionalZoomAndAdminPermissionWithoutPosition() {
    assertDoesNotThrow(() -> PersonalStartExtentService.validate(application, position(), false));
    assertDoesNotThrow(() -> PersonalStartExtentService.validate(
        application, new PersonalStartExtent().crs("EPSG:3857").mayEdit(true), true));
  }

  @Test
  void rejectsPartialAndNonfinitePositionsAndOutOfRangeZoom() {
    for (PersonalStartExtent p : List.of(
        position().center(List.of(1.0)),
        position().center(Arrays.asList(null, 2.0)),
        position().center(List.of(Double.NaN, 2.0)),
        position().zoom(Double.POSITIVE_INFINITY),
        position().zoom(-1.0),
        position().zoom(28.01),
        position().zoom(null),
        position().center(null),
        position().crs("EPSG:28992"))) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          assertThrows(
                  ResponseStatusException.class,
                  () -> PersonalStartExtentService.validate(application, p, true))
              .getStatusCode());
    }
    assertThrows(ResponseStatusException.class, () -> PersonalStartExtentService.validate(application, null, true));
    assertThrows(
        ResponseStatusException.class,
        () -> PersonalStartExtentService.validate(
            application, new PersonalStartExtent().crs("EPSG:3857"), false));
  }

  @Test
  void disabledApplicationReturnsDefaultAndPreventsWritesAndDeletesBeforeDatabaseAccess() {
    Authentication alice = local();
    PersonalStartExtent result = service.get(application, alice);
    assertEquals(false, result.getMayEdit());
    assertEquals(null, result.getCenter());
    assertThrows(ResponseStatusException.class, () -> service.save(application, alice, position()));
    assertThrows(ResponseStatusException.class, () -> service.delete(application, alice));
    verifyNoInteractions(jdbc, users);
  }

  @Test
  void anonymousAndOidcCannotResolveLocalAccountWithSameName() {
    assertThrows(ResponseStatusException.class, () -> PersonalStartExtentService.localUsername(null));
    assertThrows(
        ResponseStatusException.class,
        () -> PersonalStartExtentService.localUsername(
            new UsernamePasswordAuthenticationToken("alice", "unused")));
    Authentication oidc = UsernamePasswordAuthenticationToken.authenticated(mock(OidcUser.class), null, List.of());
    assertEquals(
        HttpStatus.FORBIDDEN,
        assertThrows(ResponseStatusException.class, () -> PersonalStartExtentService.localUsername(oidc))
            .getStatusCode());
  }

  @Test
  void userCannotGrantPermissionInWriteBodyAndPermissionIsRecheckedInMutation() {
    application.getSettings().setUiSettings(new AppUiSettings().personalStartPositionEnabled(true));
    when(users.findById("alice")).thenReturn(Optional.of(new User().setUsername("alice")));
    JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(statement.update()).thenReturn(0);
    assertEquals(
        HttpStatus.FORBIDDEN,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(
                    application, local(), position().mayEdit(true)))
            .getStatusCode());
    verify(jdbc).sql(contains("AND may_edit=true"));
    verify(statement, never()).param(eq("edit"), any());
  }

  @Test
  void clearingPositionRetainsPermissionAndOnlyAffectsCurrentAccountAndApplication() {
    application.getSettings().setUiSettings(new AppUiSettings().personalStartPositionEnabled(true));
    when(users.findById("alice")).thenReturn(Optional.of(new User().setUsername("alice")));
    JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(statement.update()).thenReturn(1);
    service.delete(application, local());
    verify(jdbc).sql(contains("center_x=NULL, center_y=NULL, zoom=NULL"));
    verify(statement).param("username", "alice");
    verify(statement).param("application", 7L);
    verify(statement, never()).param(eq("edit"), any());
  }

  @Test
  void adminCannotStoreSettingsForNonexistentLocalAccount() {
    when(users.findById("missing")).thenReturn(Optional.empty());
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(
                ResponseStatusException.class,
                () -> service.saveForUser(application, "missing", position()))
            .getStatusCode());
    verifyNoInteractions(jdbc);
  }

  @Test
  void acceptsRectangleButRejectsMixedMalformedOrNonfiniteModes() {
    PersonalStartExtent rectangle =
        new PersonalStartExtent().crs("EPSG:3857").center(null).extent(List.of(1.0, 2.0, 3.0, 4.0));
    assertDoesNotThrow(() -> PersonalStartExtentService.validate(application, rectangle, false));
    for (List<Double> extent :
        List.of(List.of(3.0, 2.0, 1.0, 4.0), List.of(1.0, 2.0, 3.0), List.of(Double.NaN, 2.0, 3.0, 4.0))) {
      assertThrows(
          ResponseStatusException.class,
          () -> PersonalStartExtentService.validate(
              application,
              new PersonalStartExtent()
                  .crs("EPSG:3857")
                  .center(null)
                  .extent(extent),
              true));
    }
    assertThrows(
        ResponseStatusException.class,
        () -> PersonalStartExtentService.validate(
            application, position().extent(List.of(1.0, 2.0, 3.0, 4.0)), false));
  }

  private PersonalStartExtent position() {
    return new PersonalStartExtent()
        .crs("EPSG:3857")
        .center(List.of(100.0, 200.0))
        .zoom(12.5);
  }

  private Authentication local() {
    return UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
  }
}
