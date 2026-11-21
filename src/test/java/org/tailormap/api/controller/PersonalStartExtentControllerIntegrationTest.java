/*
 * Copyright (C) 2026 The Tracking Solution.
 * SPDX-License-Identifier: MIT
 */
package org.tailormap.api.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.tailormap.api.TestRequestProcessor.setServletPath;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.tailormap.api.annotation.PostgresIntegrationTest;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.persistence.json.AppUiSettings;
import org.tailormap.api.repository.ApplicationRepository;
import org.tailormap.api.viewer.model.PersonalStartExtent;
import tools.jackson.databind.json.JsonMapper;

@PostgresIntegrationTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(authorities = "admin")
class PersonalStartExtentControllerIntegrationTest {
  private static final String URL = "/api/app/default/user/start-extent";
  @Autowired private MockMvc mvc;
  @Autowired private ApplicationRepository applications;
  @Autowired private JdbcClient jdbc;
  private Application application;

  @BeforeEach
  void setup() {
    for (String username : List.of("position-alice", "position-bob")) {
      jdbc.sql("INSERT INTO users (username,password,enabled) VALUES (:u,'{noop}unused',true)")
          .param("u", username).update();
    }
    application = applications.findByName("default");
    application.getSettings().setUiSettings(new AppUiSettings().personalStartPositionEnabled(true));
    applications.saveAndFlush(application);
  }

  private String adminUrl(String username) {
    return "/api/admin/applications/" + application.getId() + "/user-start-position/" + username;
  }

  private String body(boolean mayEdit) {
    return JsonMapper.shared().writeValueAsString(new PersonalStartExtent().crs(application.getCrs())
        .center(List.of(100.0, 200.0)).zoom(12.5).mayEdit(mayEdit));
  }

  private void adminSave(String username, boolean mayEdit) throws Exception {
    String url = adminUrl(username);
    mvc.perform(put(url).with(setServletPath(url)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(mayEdit)))
        .andExpect(status().isNoContent());
  }

  @Test
  void adminSetsLockedPositionUserCannotSelfGrantOrClearAndOtherAccountIsIsolated() throws Exception {
    adminSave("position-alice", false);
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-alice")))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.center[0]").value(100)).andExpect(jsonPath("$.mayEdit").value(false));
    mvc.perform(put(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isForbidden());
    mvc.perform(delete(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-bob")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.center").doesNotExist()).andExpect(jsonPath("$.mayEdit").value(false));
  }

  @Test
  void grantedUserCanSaveAndClearWithoutLosingPermissionAndRevocationTakesEffect() throws Exception {
    adminSave("position-alice", true);
    mvc.perform(put(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(false))).andExpect(status().isNoContent());
    mvc.perform(delete(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.center").doesNotExist()).andExpect(jsonPath("$.mayEdit").value(true));
    adminSave("position-alice", false);
    mvc.perform(put(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isForbidden());
  }

  @Test
  void rectangleRoundTripsAndCenterSaveReplacesIt() throws Exception {
    String admin = adminUrl("position-alice");
    String rectangle = JsonMapper.shared().writeValueAsString(new PersonalStartExtent().crs(application.getCrs())
        .center(null).extent(List.of(10.0, 20.0, 30.0, 40.0)).mayEdit(true));
    mvc.perform(put(admin).with(setServletPath(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(rectangle))
        .andExpect(status().isNoContent());
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.extent[0]").value(10))
        .andExpect(jsonPath("$.extent[3]").value(40)).andExpect(jsonPath("$.center").doesNotExist());
    mvc.perform(put(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(body(false))).andExpect(status().isNoContent());
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.center[0]").value(100))
        .andExpect(jsonPath("$.extent").doesNotExist()).andExpect(jsonPath("$.mayEdit").value(true));
  }

  @Test
  void adminEndpointsAndCsrfAreEnforced() throws Exception {
    String admin = adminUrl("position-alice");
    mvc.perform(get(admin).with(setServletPath(admin)).with(user("position-alice"))).andExpect(status().isForbidden());
    mvc.perform(put(admin).with(setServletPath(admin)).with(user("position-alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isForbidden());
    mvc.perform(put(admin).with(setServletPath(admin)).contentType(MediaType.APPLICATION_JSON).content(body(true)))
        .andExpect(status().isForbidden());
    mvc.perform(put(URL).with(setServletPath(URL)).with(user("position-alice"))
            .contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isForbidden());
  }

  @Test
  void disabledApplicationIgnoresEvenConfiguredPosition() throws Exception {
    adminSave("position-alice", true);
    application.getSettings().getUiSettings().setPersonalStartPositionEnabled(false);
    applications.saveAndFlush(application);
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.mayEdit").value(false))
        .andExpect(jsonPath("$.center").doesNotExist());
    mvc.perform(put(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isForbidden());
    mvc.perform(delete(URL).with(setServletPath(URL)).with(user("position-alice")).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void deletionCascadesPreferencesAndUserRecreationDoesNotInheritThem() throws Exception {
    adminSave("position-alice", true);
    jdbc.sql("DELETE FROM users WHERE username='position-alice'").update();
    jdbc.sql("INSERT INTO users (username,password,enabled) VALUES ('position-alice','{noop}unused',true)").update();
    mvc.perform(get(URL).with(setServletPath(URL)).with(user("position-alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.center").doesNotExist()).andExpect(jsonPath("$.mayEdit").value(false));
  }
}
