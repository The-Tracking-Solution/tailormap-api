package org.tailormap.api.configuration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Separate schema and history for the private personal-start-position extension. */
public final class TtsStartPositionMigrations {
  private static final Set<String> LEGACY_ROWS = Set.of(
      "39:V39__personal_start_extent.sql:844470457",
      "40:V40__admin_personal_start_position.sql:1089278426",
      "41:V41__user_start_position_extent.sql:1614070661");

  private TtsStartPositionMigrations() {}

  public static MigrateResult migrate(DataSource dataSource) {
    boolean adopted = inspectSchema(dataSource);
    return configured(dataSource, adopted).migrate();
  }

  static Flyway configured(DataSource dataSource, boolean adopted) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/tts-start")
        .defaultSchema("tts_start")
        .schemas("tts_start")
        .table("flyway_schema_history")
        .cleanDisabled(true)
        .baselineOnMigrate(adopted)
        .baselineVersion("0")
        .load();
  }

  static boolean inspectSchema(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      Set<String> tables = new HashSet<>();
      try (ResultSet rows =
          connection.getMetaData().getTables(null, "tts\\_start", "%", new String[] {"TABLE"})) {
        while (rows.next()) tables.add(rows.getString("TABLE_NAME"));
      }
      if (tables.contains("flyway_schema_history") || tables.isEmpty()) return false;
      if (!tables.equals(Set.of("user_start_position", "user_start_extent", "legacy_migration_history"))) {
        throw new IllegalStateException(
            "Untracked tts_start objects: run the validated adoption procedure first");
      }
      Set<String> legacy = new HashSet<>();
      int legacyCount = 0;
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(
              "SELECT version,script,checksum FROM tts_start.legacy_migration_history")) {
        while (rows.next()) {
          legacyCount++;
          legacy.add(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getInt(3));
        }
      }
      if (legacyCount != 3 || !legacy.equals(LEGACY_ROWS)) {
        throw new IllegalStateException("Legacy migration archive does not match the verified DEV history");
      }
      return true;
    } catch (SQLException ex) {
      throw new IllegalStateException("Cannot inspect the personal start-position migration schema", ex);
    }
  }
}
