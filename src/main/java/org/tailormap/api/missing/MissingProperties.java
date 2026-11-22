package org.tailormap.api.missing;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit geodata connection and group mappings; no fallback to the configuration database. */
@ConfigurationProperties("tailormap.missing")
public class MissingProperties {
  public record Organisation(String label, String group) {}

  private String url;
  private String username;
  private String password;
  private int statisticsRetentionDays = 365;
  private boolean retainExactLocations = true;
  private Map<String, Organisation> organisations = new LinkedHashMap<>();

  public String getUrl() {
    return url;
  }

  public void setUrl(String value) {
    url = value;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String value) {
    username = value;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String value) {
    password = value;
  }

  public int getStatisticsRetentionDays() {
    return statisticsRetentionDays;
  }

  public void setStatisticsRetentionDays(int value) {
    statisticsRetentionDays = value;
  }

  public boolean isRetainExactLocations() {
    return retainExactLocations;
  }

  public void setRetainExactLocations(boolean value) {
    retainExactLocations = value;
  }

  public Map<String, Organisation> getOrganisations() {
    return organisations;
  }

  public void setOrganisations(Map<String, Organisation> value) {
    organisations = value;
  }
}
