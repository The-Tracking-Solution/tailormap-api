package org.tailormap.api.missing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Intentionally not a DataSource bean: Spring must retain its ordinary Tailormap datasource. */
public final class MissingDatabase implements AutoCloseable {
  private final HikariDataSource pool;
  final NamedParameterJdbcTemplate jdbc;
  final TransactionTemplate transactions;

  public MissingDatabase(MissingProperties properties) {
    if (properties.getOrganisations().isEmpty()
        || properties.getOrganisations().entrySet().stream()
            .anyMatch(e -> !e.getKey().matches("[a-z0-9_-]{1,80}")
                || e.getValue() == null
                || e.getValue().label() == null
                || e.getValue().label().isBlank()
                || e.getValue().group() == null
                || e.getValue().group().isBlank()
                || java.util.Set.of("anonymous", "authenticated", "admin")
                    .contains(e.getValue().group()))) {
      throw new IllegalArgumentException(
          "Configure named organisations with explicit non-system Tailormap groups");
    }
    if (properties.getStatisticsRetentionDays() < 365 || properties.getStatisticsRetentionDays() > 3650) {
      throw new IllegalArgumentException("Set a missing-person statistics retention of 365–3650 days");
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(Objects.requireNonNull(properties.getUrl(), "Missing geodata URL"));
    config.setUsername(Objects.requireNonNull(properties.getUsername(), "Missing geodata username"));
    config.setPassword(Objects.requireNonNull(properties.getPassword(), "Missing geodata password"));
    config.setMaximumPoolSize(4);
    config.setPoolName("tts-missing");
    pool = new HikariDataSource(config);
    try {
      Flyway.configure()
          .dataSource(pool)
          .locations("classpath:db/tts-missing")
          .schemas("vermissingen")
          .defaultSchema("vermissingen")
          .cleanDisabled(true)
          .baselineOnMigrate(false)
          .load()
          .migrate();
    } catch (RuntimeException failure) {
      pool.close();
      throw failure;
    }
    jdbc = new NamedParameterJdbcTemplate(pool);
    transactions = new TransactionTemplate(new JdbcTransactionManager(pool));
  }

  @Override
  public void close() {
    pool.close();
  }
}
