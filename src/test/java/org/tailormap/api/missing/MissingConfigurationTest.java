package org.tailormap.api.missing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class MissingConfigurationTest {
  @Test
  void disabledByDefaultAndDoesNotCreateOrReplaceAnyDatasource() {
    try (var context = new AnnotationConfigApplicationContext(MissingConfiguration.class)) {
      assertEquals(0, context.getBeansOfType(MissingDatabase.class).size());
      assertEquals(0, context.getBeansOfType(DataSource.class).size());
      assertEquals(0, context.getBeansOfType(MissingService.class).size());
    }
  }

  @Test
  void explicitDisabledSettingAlsoLeavesDatabaseAlone() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("tailormap.missing.enabled", "false")));
      context.register(MissingConfiguration.class);
      context.refresh();
      assertEquals(0, context.getBeansOfType(MissingDatabase.class).size());
    }
  }
}
