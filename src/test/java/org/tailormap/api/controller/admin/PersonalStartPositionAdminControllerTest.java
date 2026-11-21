package org.tailormap.api.controller.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.server.ResponseStatusException;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.persistence.json.AuthorizationRule;
import org.tailormap.api.persistence.json.AuthorizationRuleDecision;
import org.tailormap.api.repository.ApplicationRepository;
import org.tailormap.api.security.AuthorisationService;
import org.tailormap.api.security.TailormapUserDetailsService;
import org.tailormap.api.service.PersonalStartExtentService;

class PersonalStartPositionAdminControllerTest {
  private final ApplicationRepository applications = mock(ApplicationRepository.class);
  private final TailormapUserDetailsService users = mock(TailormapUserDetailsService.class);
  private final PersonalStartExtentService positions = mock(PersonalStartExtentService.class);
  private final PersonalStartPositionAdminController controller =
      new PersonalStartPositionAdminController(applications, positions, users, new AuthorisationService());

  @AfterEach
  void clearContext() { SecurityContextHolder.clearContext(); }

  @Test
  void listsOnlyApplicationsAllowedForTargetUserEvenThoughCurrentAdminCanAccessEverything() {
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        "admin", null, List.of(() -> "admin")));
    when(users.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("unused").authorities("customer").build());
    Application allowed = app(1L, "allowed", "customer");
    Application denied = app(2L, "denied", "other-customer");
    when(applications.findAll()).thenReturn(List.of(denied, allowed));
    assertEquals(List.of("allowed"), controller.applications("alice").getBody().stream().map(PersonalStartPositionAdminController.StartApplication::name).toList());
    assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
  }

  @Test
  void directPreferenceReadAndWriteCannotBypassTargetUserApplicationAccess() {
    when(users.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("unused").authorities("customer").build());
    when(applications.findById(2L)).thenReturn(Optional.of(app(2L, "denied", "other")));
    assertThrows(ResponseStatusException.class, () -> controller.get(2L, "alice"));
    assertThrows(ResponseStatusException.class, () -> controller.save(2L, "alice", null));
    verifyNoInteractions(positions);
  }

  @Test
  void authenticatedAndAnonymousApplicationRulesAreIncludedForLocalUsers() {
    when(users.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("unused").authorities("customer").build());
    when(applications.findAll()).thenReturn(List.of(app(1L, "public", "anonymous"), app(2L, "members", "authenticated")));
    assertEquals(2, controller.applications("alice").getBody().size());
  }

  private Application app(Long id, String name, String group) {
    return new Application().setId(id).setName(name).setCrs("EPSG:3857").setAuthorizationRules(List.of(
        new AuthorizationRule().groupName(group).decisions(Map.of("read", AuthorizationRuleDecision.ALLOW))));
  }
}
