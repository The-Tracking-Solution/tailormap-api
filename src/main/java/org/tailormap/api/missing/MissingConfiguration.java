package org.tailormap.api.missing;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnProperty(name = "tailormap.missing.enabled", havingValue = "true")
@EnableConfigurationProperties(MissingProperties.class)
@EnableScheduling
public class MissingConfiguration {
  @Bean(destroyMethod = "close")
  MissingDatabase missingDatabase(MissingProperties properties) {
    return new MissingDatabase(properties);
  }

  @Bean
  MissingService missingService(MissingDatabase db, MissingProperties properties) {
    return new MissingService(db, properties);
  }

  @Bean
  Expiry missingExpiry(MissingDatabase db) {
    return new Expiry(db);
  }

  record Expiry(MissingDatabase db) {
    @Scheduled(fixedDelay = 3600000)
    public void purgeExpiredStatistics() {
      db.jdbc.update("DELETE FROM vermissingen.closed_stats WHERE expires_at <= now()", Map.of());
    }
  }
}
