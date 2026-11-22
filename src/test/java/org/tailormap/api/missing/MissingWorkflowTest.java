package org.tailormap.api.missing;

import static org.junit.jupiter.api.Assertions.*;
import static org.tailormap.api.missing.MissingModels.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.viewer.model.Component;
import org.tailormap.api.viewer.model.ComponentConfig;

/** Uses only a disposable local database, never the configured deployment datasource. */
@EnabledIfEnvironmentVariable(
    named = "TTS_MISSING_TEST_URL",
    matches = "jdbc:postgresql://127\\.0\\.0\\.1:55432/missing_test")
class MissingWorkflowTest {
  static MissingDatabase database;
  static MissingProperties properties;
  MissingService service;
  final Application app = new Application()
      .setId(101L)
      .setComponents(
          List.of(new Component().type("missing-persons").config(new ComponentConfig().enabled(true))));
  final Authentication a = user("alpha"), b = user("bravo"), outsider = user("charlie");

  static Authentication user(String group) {
    return new UsernamePasswordAuthenticationToken(group, "", List.of(new SimpleGrantedAuthority(group)));
  }

  @BeforeAll
  static void connect() {
    properties = new MissingProperties();
    properties.setUrl(System.getenv("TTS_MISSING_TEST_URL"));
    properties.setUsername("postgres");
    properties.setPassword("");
    properties.setStatisticsRetentionDays(365);
    properties.setRetainExactLocations(true);
    properties.setOrganisations(Map.of(
        "a",
        new MissingProperties.Organisation("Alpha", "alpha"),
        "b",
        new MissingProperties.Organisation("Bravo", "bravo"),
        "c",
        new MissingProperties.Organisation("Charlie", "charlie")));
    database = new MissingDatabase(properties);
  }

  @AfterAll
  static void disconnect() {
    if (database != null) database.close();
  }

  @BeforeEach
  void reset() {
    database.jdbc.update("DELETE FROM vermissingen.active_cases", Map.of());
    database.jdbc.update("DELETE FROM vermissingen.closed_stats", Map.of());
    service = new MissingService(database, properties);
  }

  CaseInput input(long version, List<String> organisations) {
    return new CaseInput(
        version,
        Instant.now().minusSeconds(1800),
        new Point(4.6, 52.5),
        8,
        "unknown",
        3.0,
        true,
        false,
        false,
        "a",
        organisations,
        new Intake(
            "PRIVATE-NAME",
            "PRIVATE-DESCRIPTION",
            "PRIVATE-CLOTHING",
            "PRIVATE-REPORTER",
            "PRIVATE-PHONE",
            "PRIVATE-MEDICAL"));
  }

  UUID create(List<String> organisations) {
    return UUID.fromString(
        (String) service.create(app, a, input(0, organisations)).get("id"));
  }

  CloseInput closeInput(long version) {
    return new CloseInput(version, "found", new Point(4.61, 52.51), Instant.now(), true, true, true, true);
  }

  byte[] photo() throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "jpeg", output);
    return output.toByteArray();
  }

  long count(String table) {
    return database.jdbc.queryForObject("SELECT count(*) FROM vermissingen." + table, Map.of(), Long.class);
  }

  @Test
  void membershipAndApplicationIsolationCoverEveryOperation() throws Exception {
    UUID id = create(List.of("a", "b"));
    assertEquals(1, service.list(app, b).size());
    assertTrue(service.list(app, outsider).isEmpty());
    assertThrows(ResponseStatusException.class, () -> service.get(app, outsider, id));
    assertThrows(ResponseStatusException.class, () -> service.photo(app, outsider, id));
    assertThrows(ResponseStatusException.class, () -> service.photo(app, outsider, id, 0, photo()));
    assertThrows(ResponseStatusException.class, () -> service.searchArea(app, outsider, id));
    assertThrows(
        ResponseStatusException.class,
        () -> service.addEvent(app, outsider, id, new EventInput(0, "note", "x")));
    assertThrows(ResponseStatusException.class, () -> service.close(app, outsider, id, closeInput(0)));
    app.setId(102L);
    assertTrue(service.list(app, a).isEmpty());
    assertThrows(ResponseStatusException.class, () -> service.get(app, a, id));
  }

  @Test
  void sharedOrganisationsEditButOnlyOwnerChangesSharingAndVersionsNeverOverwrite() {
    UUID id = create(List.of("a", "b"));
    service.update(app, b, id, input(0, List.of("a", "b")));
    var conflict =
        assertThrows(ResponseStatusException.class, () -> service.update(app, a, id, input(0, List.of("a"))));
    assertEquals(409, conflict.getStatusCode().value());
    assertThrows(ResponseStatusException.class, () -> service.update(app, b, id, input(1, List.of("a"))));
    service.update(app, a, id, input(1, List.of("a")));
    assertThrows(ResponseStatusException.class, () -> service.get(app, b, id));
    assertThrows(ResponseStatusException.class, () -> service.close(app, b, id, closeInput(2)));
  }

  @Test
  void closeAtomicallyDeletesAllOperationalTablesAndPreservesOnlyStructuredStatistics() throws Exception {
    UUID id = create(List.of("a", "b"));
    service.photo(app, a, id, 0, photo());
    service.addEvent(app, b, id, new EventInput(1, "note", "PRIVATE-EVENT"));
    assertTrue(((Number) service.searchArea(app, a, id).get("radiusMeters")).doubleValue() > 1499);
    service.close(app, b, id, closeInput(2));
    for (String table : List.of("active_cases", "case_organisations", "photos", "events"))
      assertEquals(0, count(table));
    assertEquals(1, count("closed_stats"));
    assertThrows(ResponseStatusException.class, () -> service.get(app, a, id));
    assertThrows(ResponseStatusException.class, () -> service.photo(app, a, id));
    var stats = service.statistics(app, a);
    assertEquals(8, stats.getFirst().get("age"));
    assertNotNull(stats.getFirst().get("lastSeenGeometry"));
    assertNotNull(stats.getFirst().get("foundGeometry"));
    String payload = database.jdbc.queryForObject(
        "SELECT row_to_json(s)::text FROM vermissingen.closed_stats s", Map.of(), String.class);
    assertFalse(payload.contains("PRIVATE-"));
    assertFalse(payload.contains(id.toString()));
    assertTrue(service.statistics(app, outsider).isEmpty());
  }

  @Test
  void failureDuringCloseRollsBackSnapshotAndKeepsPhotoAndActiveCase() throws Exception {
    UUID id = create(List.of("a"));
    service.photo(app, a, id, 0, photo());
    database.jdbc
        .getJdbcTemplate()
        .execute(
            "CREATE FUNCTION vermissingen.reject_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$");
    database.jdbc
        .getJdbcTemplate()
        .execute(
            "CREATE TRIGGER reject_delete BEFORE DELETE ON vermissingen.active_cases FOR EACH ROW EXECUTE FUNCTION vermissingen.reject_delete()");
    try {
      assertThrows(RuntimeException.class, () -> service.close(app, a, id, closeInput(1)));
      assertEquals(0, count("closed_stats"));
      assertEquals(1, count("active_cases"));
      assertEquals(1, count("photos"));
    } finally {
      database.jdbc.getJdbcTemplate().execute("DROP TRIGGER reject_delete ON vermissingen.active_cases");
      database.jdbc.getJdbcTemplate().execute("DROP FUNCTION vermissingen.reject_delete()");
    }
  }

  @Test
  void concurrentCloseCannotDuplicateStatisticsOrResurrectCase() throws Exception {
    UUID id = create(List.of("a", "b"));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Boolean> task = () -> {
        gate.await();
        try {
          service.close(app, a, id, closeInput(0));
          return true;
        } catch (ResponseStatusException conflict) {
          return false;
        }
      };
      var first = executor.submit(task);
      var second = executor.submit(task);
      gate.countDown();
      assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertEquals(1, count("closed_stats"));
      assertEquals(0, count("active_cases"));
    }
  }

  @Test
  void failedChecksCannotCloseAndWaterRiskCannotUseLandModel() {
    UUID id = create(List.of("a"));
    assertThrows(
        ResponseStatusException.class,
        () -> service.close(app, a, id, new CloseInput(0, "found", null, null, false, true, true, true)));
    assertThrows(
        ResponseStatusException.class,
        () -> service.close(app, a, id, new CloseInput(0, "other", null, null, false, true, true, false)));
    var i = input(0, List.of("a"));
    service.update(
        app,
        a,
        id,
        new CaseInput(
            i.version(),
            i.lastSeenAt(),
            i.lastSeen(),
            i.age(),
            i.sex(),
            i.speedKmh(),
            true,
            true,
            false,
            i.ownerOrganisation(),
            i.organisations(),
            i.intake()));
    assertThrows(ResponseStatusException.class, () -> service.searchArea(app, a, id));
    assertEquals(1, count("active_cases"));
  }

  @Test
  void expiresStatisticsAndNeverRecreatesClosedOperationalData() throws Exception {
    UUID id = create(List.of("a"));
    service.close(app, a, id, closeInput(0));
    assertTrue(database.jdbc.queryForObject(
        "SELECT expires_at >= closed_at + interval '1 year' FROM vermissingen.closed_stats",
        Map.of(),
        Boolean.class));
    assertThrows(ResponseStatusException.class, () -> service.photo(app, a, id, 0, photo()));
    assertEquals(0, count("photos"));
    database.jdbc.update("UPDATE vermissingen.closed_stats SET expires_at=now()-interval '1 second'", Map.of());
    assertTrue(service.statistics(app, a).isEmpty());
    new MissingConfiguration.Expiry(database).purgeExpiredStatistics();
    assertEquals(0, count("closed_stats"));
  }

  @Test
  void applicationSwitchCannotBeBypassedByDirectApiUse() {
    UUID id = create(List.of("a"));
    app.setComponents(List.of());
    assertThrows(ResponseStatusException.class, () -> service.get(app, a, id));
    assertThrows(ResponseStatusException.class, () -> service.configuration(app, a));
    assertThrows(ResponseStatusException.class, () -> service.close(app, a, id, closeInput(0)));
  }
}
