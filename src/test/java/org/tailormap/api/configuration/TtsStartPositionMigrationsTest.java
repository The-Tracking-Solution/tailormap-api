package org.tailormap.api.configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class TtsStartPositionMigrationsTest {
  @Test
  void configurationIsSeparateAndCannotClean() {
    var config = TtsStartPositionMigrations.configured(new PGSimpleDataSource(), false)
        .getConfiguration();
    assertEquals("tts_start", config.getDefaultSchema());
    assertArrayEquals(new String[] {"tts_start"}, config.getSchemas());
    assertEquals("flyway_schema_history", config.getTable());
    assertTrue(config.isCleanDisabled());
    assertFalse(config.isBaselineOnMigrate());
    assertEquals("0", config.getBaselineVersion().getVersion());
    assertEquals("classpath:db/tts-start", config.getLocations()[0].toString());
  }

  @Test
  void verifiedAdoptionEnablesOnlyBaselineZero() {
    var config = TtsStartPositionMigrations.configured(new PGSimpleDataSource(), true)
        .getConfiguration();
    assertTrue(config.isBaselineOnMigrate());
    assertEquals("0", config.getBaselineVersion().getVersion());
    assertTrue(config.isValidateOnMigrate());
  }

  private DataSource schema(String... tableNames) throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    ResultSet tables = mock(ResultSet.class);
    when(ds.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getTables(isNull(), eq("tts\\_start"), eq("%"), any())).thenReturn(tables);
    int[] index = {-1};
    when(tables.next()).thenAnswer(call -> ++index[0] < tableNames.length);
    when(tables.getString("TABLE_NAME")).thenAnswer(call -> tableNames[index[0]]);
    return ds;
  }

  @Test
  void emptyNamespaceNeedsNoBaseline() throws Exception {
    assertFalse(TtsStartPositionMigrations.inspectSchema(schema()));
  }

  @Test
  void existingHistoryUsesNormalValidation() throws Exception {
    assertFalse(TtsStartPositionMigrations.inspectSchema(schema("flyway_schema_history", "user_start_position")));
  }

  @Test
  void untrackedPrototypeCannotBeSilentlyBaselined() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> TtsStartPositionMigrations.inspectSchema(schema("user_start_position")));
  }

  @Test
  void missingLegacyArchiveCannotBeSilentlyBaselined() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> TtsStartPositionMigrations.inspectSchema(schema("user_start_position", "user_start_extent")));
  }

  @Test
  void databaseErrorNeverTurnsIntoBaselinePermission() throws Exception {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("unavailable"));
    assertThrows(IllegalStateException.class, () -> TtsStartPositionMigrations.inspectSchema(ds));
  }

  private DataSource archive(int lastChecksum) throws Exception {
    var ds = schema("user_start_position", "user_start_extent", "legacy_migration_history");
    var statement = mock(Statement.class);
    var rows = mock(ResultSet.class);
    when(ds.getConnection().createStatement()).thenReturn(statement);
    when(statement.executeQuery(anyString())).thenReturn(rows);
    when(rows.next()).thenReturn(true, true, true, false);
    when(rows.getString(1)).thenReturn("39", "40", "41");
    when(rows.getString(2))
        .thenReturn(
            "V39__personal_start_extent.sql",
            "V40__admin_personal_start_position.sql",
            "V41__user_start_position_extent.sql");
    when(rows.getInt(3)).thenReturn(844470457, 1089278426, lastChecksum);
    return ds;
  }

  @Test
  void exactLegacyArchiveAllowsAdoption() throws Exception {
    assertTrue(TtsStartPositionMigrations.inspectSchema(archive(1614070661)));
  }

  @Test
  void differentLegacyChecksumBlocksAdoption() throws Exception {
    var ds = archive(1);
    assertThrows(IllegalStateException.class, () -> TtsStartPositionMigrations.inspectSchema(ds));
  }
}
