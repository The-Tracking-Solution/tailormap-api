package org.tailormap.api.missing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class MissingAccessTest {
  @Test
  void onlyExplicitOrganisationMembershipGrantsCaseAccess() {
    var properties = new MissingProperties();
    properties.setOrganisations(
        Map.of("heemskerk", new MissingProperties.Organisation("Heemskerk", "rb-heemskerk")));
    var service = new MissingService(null, properties);
    assertThrows(ResponseStatusException.class, () -> service.membership(null));
    assertThrows(
        ResponseStatusException.class,
        () -> service.membership(new AnonymousAuthenticationToken(
            "key", "anon", List.of(new SimpleGrantedAuthority("rb-heemskerk")))));
    assertThrows(
        ResponseStatusException.class,
        () -> service.membership(new UsernamePasswordAuthenticationToken(
            "admin", "", List.of(new SimpleGrantedAuthority("admin")))));
    assertEquals(
        List.of("heemskerk"),
        service.membership(new UsernamePasswordAuthenticationToken(
            "user", "", List.of(new SimpleGrantedAuthority("rb-heemskerk")))));
  }

  @Test
  void invalidCoordinatesNeverReachPostgis() {
    assertThrows(
        ResponseStatusException.class,
        () -> MissingService.validatePoint(new MissingModels.Point(Double.NaN, 52)));
    assertThrows(ResponseStatusException.class, () -> MissingService.validatePoint(new MissingModels.Point(4, 91)));
    MissingService.validatePoint(new MissingModels.Point(4.6, 52.5));
  }
}
