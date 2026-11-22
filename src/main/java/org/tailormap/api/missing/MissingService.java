package org.tailormap.api.missing;

import static org.tailormap.api.missing.MissingModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.tailormap.api.persistence.Application;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** All case mutations serialize on the case row, including sharing, photos, events and close. */
public class MissingService {
  private final MissingDatabase db;
  private final MissingProperties properties;
  private final JsonMapper json = new JsonMapper();

  public MissingService(MissingDatabase db, MissingProperties properties) {
    this.db = db;
    this.properties = properties;
  }

  List<String> membership(Authentication auth) {
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    var authorities =
        auth.getAuthorities().stream().map(a -> a.getAuthority()).toList();
    var result = properties.getOrganisations().entrySet().stream()
        .filter(e -> !Set.of("anonymous", "authenticated", "admin")
            .contains(e.getValue().group()))
        .filter(e -> authorities.contains(e.getValue().group()))
        .map(Map.Entry::getKey)
        .toList();
    if (result.isEmpty())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No missing-person organisation assigned");
    return result;
  }

  void enabled(Application app) {
    if (app == null
        || app.getId() == null
        || app.getComponents().stream()
            .noneMatch(c -> "missing-persons".equals(c.getType())
                && c.getConfig() != null
                && Boolean.TRUE.equals(c.getConfig().getEnabled()))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  public Map<String, Object> configuration(Application app, Authentication auth) {
    enabled(app);
    var own = membership(auth);
    return Map.of(
        "organisations",
        properties.getOrganisations().entrySet().stream()
            .map(e -> Map.of(
                "id", e.getKey(), "label", e.getValue().label(), "member", own.contains(e.getKey())))
            .toList(),
        "retainExactLocations",
        properties.isRetainExactLocations(),
        "statisticsRetentionDays",
        properties.getStatisticsRetentionDays());
  }

  private Map<String, Object> parameters(Application app, Authentication auth) {
    enabled(app);
    return new HashMap<>(Map.of("app", app.getId(), "members", membership(auth)));
  }

  public List<Map<String, Object>> list(Application app, Authentication auth) {
    return db.jdbc.query("""
SELECT c.*, EXISTS(SELECT 1 FROM vermissingen.photos p WHERE p.case_id=c.id) AS has_photo
FROM vermissingen.active_cases c WHERE application_id=:app
AND EXISTS(SELECT 1 FROM vermissingen.case_organisations o WHERE o.case_id=c.id AND o.organisation_id IN (:members))
ORDER BY last_seen_at
""", parameters(app, auth), (rs, n) -> row(rs));
  }

  private Map<String, Object> row(ResultSet rs) throws SQLException {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", rs.getObject("id").toString());
    result.put("version", rs.getLong("version"));
    result.put("lastSeenAt", rs.getTimestamp("last_seen_at").toInstant());
    result.put("age", rs.getObject("age"));
    result.put("sex", rs.getString("sex"));
    result.put("speedKmh", rs.getObject("speed_kmh"));
    result.put("waterRisk", rs.getBoolean("water_risk"));
    result.put("urgent", rs.getBoolean("urgent"));
    result.put("ownerOrganisation", rs.getString("owner_organisation"));
    result.put(
        "intake", json.readValue(rs.getString("private_intake"), new TypeReference<Map<String, Object>>() {}));
    result.put("hasPhoto", rs.getBoolean("has_photo"));
    return result;
  }

  private Map<String, Object> load(Application app, Authentication auth, UUID id, boolean lock) {
    var params = parameters(app, auth);
    params.put("id", id);
    var rows = db.jdbc.query("""
SELECT c.*, EXISTS(SELECT 1 FROM vermissingen.photos p WHERE p.case_id=c.id) AS has_photo
FROM vermissingen.active_cases c WHERE c.id=:id AND application_id=:app
AND EXISTS(SELECT 1 FROM vermissingen.case_organisations o WHERE o.case_id=c.id AND o.organisation_id IN (:members))
""" + (lock ? " FOR UPDATE OF c" : ""), params, (rs, n) -> row(rs));
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var result = rows.getFirst();
    var organisations = db.jdbc.queryForList(
        "SELECT organisation_id FROM vermissingen.case_organisations WHERE case_id=:id ORDER BY organisation_id",
        params,
        String.class);
    // Recheck after acquiring the row lock: a sharing change may have committed while we waited.
    if (Collections.disjoint(organisations, membership(auth)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    result.put("organisations", organisations);
    result.put(
        "lastSeen",
        db.jdbc.queryForObject(
            "SELECT ST_X(last_seen_geom) AS lon, ST_Y(last_seen_geom) AS lat FROM vermissingen.active_cases WHERE id=:id",
            params,
            (rs, n) -> new Point(rs.getDouble("lon"), rs.getDouble("lat"))));
    return result;
  }

  public Map<String, Object> get(Application app, Authentication auth, UUID id) {
    return db.transactions.execute(status -> {
      var result = load(app, auth, id, true);
      result.put(
          "events",
          db.jdbc.queryForList(
              "SELECT id, occurred_at AS \"occurredAt\", kind, body FROM vermissingen.events WHERE case_id=:id ORDER BY occurred_at,id",
              Map.of("id", id)));
      return result;
    });
  }

  static void validatePoint(Point point) {
    if (point == null
        || !Double.isFinite(point.lon())
        || !Double.isFinite(point.lat())
        || Math.abs(point.lon()) > 180
        || Math.abs(point.lat()) > 90) bad("Invalid coordinates");
  }

  private void validate(CaseInput input, Authentication auth) {
    validatePoint(input.lastSeen());
    if (!membership(auth).contains(input.ownerOrganisation())) bad("Select one of your own organisations as owner");
    if (!input.organisations().contains(input.ownerOrganisation())
        || !properties.getOrganisations().keySet().containsAll(input.organisations()))
      bad("Invalid organisation selection");
    if (input.speedKmh() != null && (!Double.isFinite(input.speedKmh()) || !input.speedConfirmed())) {
      bad("Confirm the indicative walking speed explicitly");
    }
  }

  public Map<String, Object> create(Application app, Authentication auth, CaseInput input) {
    enabled(app);
    validate(input, auth);
    UUID id = UUID.randomUUID();
    return db.transactions.execute(status -> {
      var p = inputParameters(app, input, id);
      db.jdbc.update("""
INSERT INTO vermissingen.active_cases(id,application_id,owner_organisation,last_seen_at,last_seen_geom,
age,sex,speed_kmh,water_risk,urgent,private_intake)
VALUES(:id,:app,:owner,:seen,ST_SetSRID(ST_MakePoint(:lon,:lat),4326),:age,:sex,:speed,:water,:urgent,CAST(:intake AS jsonb))
""", p);
      share(id, input.organisations());
      return load(app, auth, id, false);
    });
  }

  private Map<String, Object> inputParameters(Application app, CaseInput input, UUID id) {
    var p = new HashMap<String, Object>();
    p.put("id", id);
    p.put("app", app.getId());
    p.put("owner", input.ownerOrganisation());
    p.put("seen", Timestamp.from(input.lastSeenAt()));
    p.put("lon", input.lastSeen().lon());
    p.put("lat", input.lastSeen().lat());
    p.put("age", input.age());
    p.put("sex", input.sex());
    p.put("speed", input.speedKmh());
    p.put("water", input.waterRisk());
    p.put("urgent", input.urgent());
    p.put("intake", json.writeValueAsString(input.intake()));
    return p;
  }

  private void share(UUID id, List<String> organisations) {
    db.jdbc.update("DELETE FROM vermissingen.case_organisations WHERE case_id=:id", Map.of("id", id));
    for (String organisation : new HashSet<>(organisations)) {
      db.jdbc.update(
          "INSERT INTO vermissingen.case_organisations VALUES(:id,:organisation)",
          Map.of("id", id, "organisation", organisation));
    }
  }

  private Map<String, Object> lock(Application app, Authentication auth, UUID id, long version) {
    var current = load(app, auth, id, true);
    if (((Number) current.get("version")).longValue() != version) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case changed; reload before saving");
    }
    return current;
  }

  public Map<String, Object> update(Application app, Authentication auth, UUID id, CaseInput input) {
    // Shared organisations may edit the case, but only the owner may change its sharing.
    enabled(app);
    membership(auth);
    validatePoint(input.lastSeen());
    if (input.speedKmh() != null && (!Double.isFinite(input.speedKmh()) || !input.speedConfirmed()))
      bad("Confirm the walking speed");
    return db.transactions.execute(status -> {
      var current = lock(app, auth, id, input.version());
      if (!Objects.equals(current.get("ownerOrganisation"), input.ownerOrganisation()))
        bad("Owner cannot be changed");
      var existing = new HashSet<>((List<?>) current.get("organisations"));
      if (!existing.equals(new HashSet<>(input.organisations()))) {
        validate(input, auth);
        share(id, input.organisations());
      }
      db.jdbc.update("""
UPDATE vermissingen.active_cases SET last_seen_at=:seen,last_seen_geom=ST_SetSRID(ST_MakePoint(:lon,:lat),4326),
age=:age,sex=:sex,speed_kmh=:speed,water_risk=:water,urgent=:urgent,private_intake=CAST(:intake AS jsonb),
version=version+1,updated_at=now() WHERE id=:id
""", inputParameters(app, input, id));
      return load(app, auth, id, false);
    });
  }

  public void addEvent(Application app, Authentication auth, UUID id, EventInput input) {
    db.transactions.executeWithoutResult(status -> {
      lock(app, auth, id, input.version());
      db.jdbc.update(
          "INSERT INTO vermissingen.events(id,case_id,kind,body) VALUES(:event,:id,:kind,:body)",
          Map.of("event", UUID.randomUUID(), "id", id, "kind", input.kind(), "body", input.body()));
      bump(id);
    });
  }

  private void bump(UUID id) {
    db.jdbc.update(
        "UPDATE vermissingen.active_cases SET version=version+1,updated_at=now() WHERE id=:id",
        Map.of("id", id));
  }

  public void photo(Application app, Authentication auth, UUID id, long version, byte[] bytes) {
    // Check access before expensive image processing, then recheck under the mutation lock.
    load(app, auth, id, false);
    byte[] clean = MissingPhoto.sanitize(bytes);
    db.transactions.executeWithoutResult(status -> {
      lock(app, auth, id, version);
      db.jdbc.update(
          "INSERT INTO vermissingen.photos VALUES(:id,:jpeg) ON CONFLICT(case_id) DO UPDATE SET jpeg=:jpeg",
          Map.of("id", id, "jpeg", clean));
      bump(id);
    });
  }

  public byte[] photo(Application app, Authentication auth, UUID id) {
    return db.transactions.execute(status -> {
      load(app, auth, id, true);
      var rows = db.jdbc.queryForList(
          "SELECT jpeg FROM vermissingen.photos WHERE case_id=:id", Map.of("id", id), byte[].class);
      if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      return rows.getFirst();
    });
  }

  public Map<String, Object> searchArea(Application app, Authentication auth, UUID id) {
    return db.transactions.execute(status -> {
      var current = load(app, auth, id, true);
      if (Boolean.TRUE.equals(current.get("waterRisk")))
        bad("Water risk: a land buffer is not a water search model");
      if (current.get("speedKmh") == null) bad("Select and confirm a walking speed first");
      Instant now = Instant.now();
      double minutes = Math.max(
          0,
          Duration.between((Instant) current.get("lastSeenAt"), now).toMillis() / 60000.0);
      double radius = ((Number) current.get("speedKmh")).doubleValue() * 1000 / 60 * minutes;
      if (radius > 200000) bad("Indicative radius exceeds 200 km; reassess the model");
      String wkt = db.jdbc.queryForObject(
          "SELECT ST_AsText(ST_Buffer(last_seen_geom::geography,:radius)::geometry) FROM vermissingen.active_cases WHERE id=:id",
          Map.of("id", id, "radius", Math.max(0.01, radius)),
          String.class);
      return Map.of(
          "geometry",
          wkt,
          "crs",
          "EPSG:4326",
          "radiusMeters",
          radius,
          "calculatedAt",
          now,
          "method",
          "indicative_buffer");
    });
  }

  public Map<String, Object> close(Application app, Authentication auth, UUID id, CloseInput input) {
    if (!input.confirmDelete() || !input.policeConsidered() || !input.careConsidered())
      bad("Complete the closing checks");
    if ("found".equals(input.outcome())) {
      validatePoint(input.found());
      if (input.foundAt() == null || !input.personVerified())
        bad("Record finding time/location and verify the person");
    } else if (input.found() != null || input.foundAt() != null) bad("Finding data requires outcome found");
    return db.transactions.execute(status -> {
      var current = lock(app, auth, id, input.version());
      if (input.foundAt() != null && input.foundAt().isBefore((Instant) current.get("lastSeenAt")))
        bad("Finding precedes last observation");
      var p = new HashMap<String, Object>();
      p.put("id", id);
      p.put("stats", UUID.randomUUID());
      p.put("retain", properties.isRetainExactLocations());
      p.put("outcome", input.outcome());
      p.put("foundAt", input.foundAt() == null ? null : Timestamp.from(input.foundAt()));
      p.put("lon", input.found() == null ? null : input.found().lon());
      p.put("lat", input.found() == null ? null : input.found().lat());
      p.put("days", properties.getStatisticsRetentionDays());
      db.jdbc.update("""
INSERT INTO vermissingen.closed_stats(id,application_id,organisations,age,sex,last_seen_at,last_seen_geom,
found_at,found_geom,outcome,speed_kmh,expires_at)
SELECT :stats,application_id,ARRAY(SELECT organisation_id FROM vermissingen.case_organisations WHERE case_id=:id),
age,sex,last_seen_at,CASE WHEN :retain THEN last_seen_geom END,:foundAt,
CASE WHEN :retain AND CAST(:lon AS double precision) IS NOT NULL THEN ST_SetSRID(ST_MakePoint(:lon,:lat),4326) END,
:outcome,speed_kmh,GREATEST(now()+interval '1 year',now()+make_interval(days=>:days))
FROM vermissingen.active_cases WHERE id=:id
""", p);
      // Cascades remove photo and private timeline in the SAME geodata transaction as the snapshot.
      db.jdbc.update("DELETE FROM vermissingen.active_cases WHERE id=:id", p);
      return Map.of("closed", true, "privacyCompleted", true);
    });
  }

  public List<Map<String, Object>> statistics(Application app, Authentication auth) {
    var rows = db.jdbc.queryForList("""
SELECT id,age,sex,last_seen_at AS "lastSeenAt",found_at AS "foundAt",outcome,closed_at AS "closedAt",
ST_AsText(last_seen_geom) AS "lastSeenGeometry",ST_AsText(found_geom) AS "foundGeometry",environment
FROM vermissingen.closed_stats s WHERE application_id=:app AND expires_at>now()
AND EXISTS(SELECT 1 FROM unnest(s.organisations) o WHERE o IN (:members)) ORDER BY closed_at DESC LIMIT 500
""", parameters(app, auth));
    rows.forEach(row -> row.computeIfPresent(
        "environment",
        (key, value) -> json.readValue(value.toString(), new TypeReference<Map<String, Object>>() {})));
    return rows;
  }

  private static void bad(String reason) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
