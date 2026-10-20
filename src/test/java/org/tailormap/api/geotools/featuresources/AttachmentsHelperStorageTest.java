/*
 * Copyright (C) 2026 The Tracking Solution
 *
 * SPDX-License-Identifier: MIT
 */
package org.tailormap.api.geotools.featuresources;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.commons.dbcp.DelegatingConnection;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.jdbc.JDBCDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.tailormap.api.persistence.TMFeatureType;
import org.tailormap.api.persistence.json.JDBCConnectionProperties.DbtypeEnum;

class AttachmentsHelperStorageTest {
  private final TMFeatureType featureType = mock(TMFeatureType.class, RETURNS_DEEP_STUBS);
  private final JDBCDataStore datastore = mock(JDBCDataStore.class);
  private final Connection connection = mock(Connection.class);
  private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
  private final ResultSet tables = mock(ResultSet.class);
  private final ResultSet indexes = mock(ResultSet.class);

  @BeforeEach
  void setUp() throws Exception {
    when(featureType.getName()).thenReturn("webcams");
    when(featureType.getFeatureSource().getJdbcConnection().getDbtype()).thenReturn(DbtypeEnum.POSTGIS);
    when(datastore.getDatabaseSchema()).thenReturn("public");
    when(connection.getCatalog()).thenReturn("geodata");
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getSearchStringEscape()).thenReturn("\\");
    when(metadata.getTables(eq("geodata"), eq("public"), anyString(), any(String[].class)))
        .thenReturn(tables);
    when(tables.next()).thenReturn(true, false);
    when(tables.getString("TABLE_NAME")).thenReturn("webcams_attachments");
    when(metadata.getIndexInfo("geodata", "public", "webcams_attachments", false, false))
        .thenReturn(indexes);
    when(indexes.next()).thenReturn(true, false);
    when(indexes.getString("INDEX_NAME")).thenReturn("webcams_attachments_fk");
  }

  @ParameterizedTest
  @EnumSource(
      value = DbtypeEnum.class,
      names = {"POSTGIS", "SQLSERVER", "ORACLE"})
  void existing_storage_does_not_execute_ddl_even_on_read_only_sources(DbtypeEnum dbtype) throws Exception {
    when(featureType.getFeatureSource().getJdbcConnection().getDbtype()).thenReturn(dbtype);
    if (dbtype == DbtypeEnum.ORACLE) {
      when(tables.getString("TABLE_NAME")).thenReturn("WEBCAMS_ATTACHMENTS");
      when(metadata.getIndexInfo("geodata", "public", "WEBCAMS_ATTACHMENTS", false, false))
          .thenReturn(indexes);
      when(indexes.getString("INDEX_NAME")).thenReturn("WEBCAMS_ATTACHMENTS_FK");
    }
    // Reproduce a replica rejecting DDL, rather than relying on the JDBC readOnly flag.
    when(connection.createStatement())
        .thenThrow(new SQLException("cannot execute CREATE TABLE in a read-only transaction"));
    AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection);
    verify(connection, never()).createStatement();
    verify(datastore, never()).getDataSource();
    verify(tables).close();
    verify(indexes).close();
  }

  @Test
  void table_metadata_escapes_wildcards_and_index_metadata_uses_exact_name() throws Exception {
    AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection);
    verify(metadata).getTables(eq("geodata"), eq("public"), eq("webcams\\_attachments"), any(String[].class));
    verify(metadata).getIndexInfo("geodata", "public", "webcams_attachments", false, false);
  }

  @Test
  void existing_table_with_missing_index_only_creates_index() throws Exception {
    when(indexes.next()).thenReturn(false);
    Statement statement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement);
    AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection);
    verify(statement)
        .execute("CREATE INDEX IF NOT EXISTS webcams_attachments_fk ON public.webcams_attachments(webcams_pk)");
    verify(connection, times(1)).createStatement();
    verify(statement).close();
  }

  @Test
  void missing_storage_creates_table_and_index() throws Exception {
    when(tables.next()).thenReturn(false);
    when(indexes.next()).thenReturn(false);
    when(featureType.getPrimaryKeyAttribute()).thenReturn("id");
    SimpleFeatureType schema = mock(SimpleFeatureType.class);
    AttributeDescriptor pk = mock(AttributeDescriptor.class, RETURNS_DEEP_STUBS);
    when(datastore.getSchema("webcams")).thenReturn(schema);
    when(schema.getDescriptor("id")).thenReturn(pk);
    when(pk.getLocalName()).thenReturn("id");
    DelegatingConnection pooled = mock(DelegatingConnection.class);
    DataSource datasource = mock(DataSource.class);
    when(datastore.getDataSource()).thenReturn(datasource);
    when(datasource.getConnection()).thenReturn(pooled);
    when(pooled.getInnermostDelegate()).thenReturn(connection);
    ResultSet columns = mock(ResultSet.class);
    when(metadata.getColumns("geodata", "public", "webcams", "id")).thenReturn(columns);
    when(columns.next()).thenReturn(true);
    when(columns.getString("TYPE_NAME")).thenReturn("int4");
    Statement statement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement);
    AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection);
    verify(statement).execute(ArgumentMatchers.startsWith("CREATE TABLE IF NOT EXISTS public.webcams_attachments"));
    verify(statement)
        .execute("CREATE INDEX IF NOT EXISTS webcams_attachments_fk ON public.webcams_attachments(webcams_pk)");
    verify(connection, times(2)).createStatement();
  }

  @Test
  void unspecified_schema_uses_connection_schema() throws Exception {
    when(datastore.getDatabaseSchema()).thenReturn("");
    when(connection.getSchema()).thenReturn("public");
    AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection);
    verify(connection, never()).createStatement();
  }

  @Test
  void metadata_errors_are_not_silently_ignored() throws Exception {
    when(metadata.getIndexInfo("geodata", "public", "webcams_attachments", false, false))
        .thenThrow(new SQLException("metadata unavailable"));
    assertThrows(
        SQLException.class, () -> AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection));
    verify(connection, never()).createStatement();
  }

  @Test
  void missing_index_on_read_only_source_still_reports_failure() throws Exception {
    when(indexes.next()).thenReturn(false);
    when(connection.createStatement()).thenThrow(new SQLException("read-only transaction"));
    assertThrows(
        SQLException.class, () -> AttachmentsHelper.ensureAttachmentTable(featureType, datastore, connection));
  }
}
