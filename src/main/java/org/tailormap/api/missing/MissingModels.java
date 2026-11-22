package org.tailormap.api.missing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class MissingModels {
  private MissingModels() {}

  public record Point(
      @DecimalMin("-180") @DecimalMax("180") double lon,
      @DecimalMin("-90") @DecimalMax("90") double lat) {}

  public record Intake(
      @Size(max = 100) String name,
      @Size(max = 500) String description,
      @Size(max = 1000) String clothing,
      @Size(max = 150) String reporter,
      @Size(max = 80) String telephone,
      @Size(max = 2000) String details) {}

  public record CaseInput(
      @PositiveOrZero long version,
      @NotNull @PastOrPresent Instant lastSeenAt,
      @NotNull @Valid Point lastSeen,
      @Min(0) @Max(120) Integer age,

      @NotNull @Pattern(regexp = "unknown|female|male|other|not_specified") String sex,

      @DecimalMin(value = "0", inclusive = false) @DecimalMax("15") Double speedKmh,

      boolean speedConfirmed,
      boolean waterRisk,
      boolean urgent,
      @NotBlank @Size(max = 80) String ownerOrganisation,
      @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 80) String> organisations,
      @NotNull @Valid Intake intake) {}

  public record EventInput(
      @PositiveOrZero long version,

      @NotNull @Pattern(regexp = "note|police|handover|found") String kind,

      @NotBlank @Size(max = 2000) String body) {}

  public record CloseInput(
      @PositiveOrZero long version,

      @NotNull @Pattern(regexp = "found|transferred|not_found|other") String outcome,

      @Valid Point found,
      @PastOrPresent Instant foundAt,
      boolean personVerified,
      boolean policeConsidered,
      boolean careConsidered,
      boolean confirmDelete) {}
}
