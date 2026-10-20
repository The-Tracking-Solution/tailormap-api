/*
 * Copyright (C) 2023 B3Partners B.V.
 *
 * SPDX-License-Identifier: MIT
 */

package org.tailormap.api.persistence.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.tailormap.api.persistence.helper.TMFeatureTypeHelper.getConfiguredAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.tailormap.api.persistence.Application;
import org.tailormap.api.persistence.TMFeatureType;
import org.tailormap.api.persistence.json.AppLayerSettings;
import org.tailormap.api.persistence.json.AppSettings;
import org.tailormap.api.persistence.json.AttributeDisplayType;
import org.tailormap.api.persistence.json.AttributeSettings;
import org.tailormap.api.persistence.json.FeatureTypeSettings;
import org.tailormap.api.persistence.json.TMAttributeDescriptor;
import org.tailormap.api.persistence.json.TMAttributeType;
import org.tailormap.api.viewer.model.ColumnMetadata;
import tools.jackson.databind.json.JsonMapper;

class TMFeatureTypeHelperTest {

  @Test
  void get_configured_attributes() {
    TMFeatureType ft = new TMFeatureType();
    final Function<String, TMAttributeDescriptor> att =
        (name) -> new TMAttributeDescriptor().name(name).comment("comment for " + name);
    ft.setAttributes(List.of(
        att.apply("a"),
        att.apply("b"),
        att.apply("c"),
        att.apply("d"),
        att.apply("e"),
        att.apply("f"),
        att.apply("m"),
        att.apply("n"),
        att.apply("o"),
        att.apply("q")));

    ft.setSettings(new FeatureTypeSettings()
        .putAttributeSettingsItem("d", new AttributeSettings().title("d title"))
        .addHideAttributesItem("b")
        .addHideAttributesItem("q")
        .addHideAttributesItem("o")
        .addAttributeOrderItem("c")
        .addAttributeOrderItem("d")
        .addAttributeOrderItem("e")
        .addAttributeOrderItem("o")
        .addAttributeOrderItem("a"));

    AppLayerSettings appLayerSettings = new AppLayerSettings();
    appLayerSettings.setHideAttributes(List.of("e", "f"));

    Map<String, TMFeatureTypeHelper.AttributeWithSettings> configuredAttributes =
        getConfiguredAttributes(ft, appLayerSettings);
    // Compare using Lists to explicitly check the ordering
    assertEquals(
        List.of(
            "c",
            "d",
            // "e" and "f" are hidden in app layer settings
            // "o" is hidden in feature type settings
            "a",
            // after ordered attributes, remaining attributes in original feature type order
            "m",
            "n"),
        new ArrayList<>(configuredAttributes.keySet()));

    assertEquals("d title", configuredAttributes.get("d").settings().getTitle());

    assertEquals(
        "comment for m",
        configuredAttributes.get("m").attributeDescriptor().getComment());
  }

  @Test
  void display_type_inherits_and_overrides_without_modifying_catalog() {
    TMFeatureType ft = new TMFeatureType()
        .setAttributes(
            List.of(new TMAttributeDescriptor().name("camera_url").type(TMAttributeType.STRING)))
        .setSettings(new FeatureTypeSettings()
            .putAttributeSettingsItem(
                "camera_url",
                new AttributeSettings().title("Camera").displayType(AttributeDisplayType.VIDEO)));
    AppLayerSettings settings = new AppLayerSettings();
    assertEquals(
        AttributeDisplayType.VIDEO,
        getConfiguredAttributes(ft, settings).get("camera_url").displayType());
    settings.putAttributeDisplayOverridesItem("camera_url", AttributeDisplayType.TEXT);
    assertEquals(
        AttributeDisplayType.TEXT,
        getConfiguredAttributes(ft, settings).get("camera_url").displayType());
    assertEquals(
        "Camera",
        getConfiguredAttributes(ft, settings)
            .get("camera_url")
            .settings()
            .getTitle());
    assertEquals(
        AttributeDisplayType.VIDEO,
        ft.getSettings().getAttributeSettings().get("camera_url").getDisplayType());
    // Removing an override must resume inheritance, rather than copying the old catalog value.
    settings.getAttributeDisplayOverrides().clear();
    ft.getSettings().getAttributeSettings().get("camera_url").setDisplayType(AttributeDisplayType.TEXT);
    assertEquals(
        AttributeDisplayType.TEXT,
        getConfiguredAttributes(ft, settings).get("camera_url").displayType());
  }

  @Test
  void defaults_to_text_and_layer_can_enable_video() {
    TMFeatureType ft = new TMFeatureType()
        .setAttributes(List.of(new TMAttributeDescriptor().name("url").type(TMAttributeType.STRING)));
    AppLayerSettings settings = new AppLayerSettings();
    assertEquals(
        AttributeDisplayType.TEXT,
        getConfiguredAttributes(ft, settings).get("url").displayType());
    settings.putAttributeDisplayOverridesItem("url", AttributeDisplayType.VIDEO);
    assertEquals(
        AttributeDisplayType.VIDEO,
        getConfiguredAttributes(ft, settings).get("url").displayType());
  }

  @Test
  void display_override_cannot_expose_hidden_or_unknown_attributes_or_play_non_strings() {
    TMFeatureType ft = new TMFeatureType()
        .setAttributes(List.of(
            new TMAttributeDescriptor().name("catalog_hidden").type(TMAttributeType.STRING),
            new TMAttributeDescriptor().name("layer_hidden").type(TMAttributeType.STRING),
            new TMAttributeDescriptor().name("number").type(TMAttributeType.INTEGER)))
        .setSettings(new FeatureTypeSettings().addHideAttributesItem("catalog_hidden"));
    AppLayerSettings settings = new AppLayerSettings()
        .addHideAttributesItem("layer_hidden")
        .putAttributeDisplayOverridesItem("catalog_hidden", AttributeDisplayType.VIDEO)
        .putAttributeDisplayOverridesItem("layer_hidden", AttributeDisplayType.VIDEO)
        .putAttributeDisplayOverridesItem("unknown", AttributeDisplayType.VIDEO)
        .putAttributeDisplayOverridesItem("number", AttributeDisplayType.VIDEO);
    var attributes = getConfiguredAttributes(ft, settings);
    assertEquals(List.of("number"), new ArrayList<>(attributes.keySet()));
    assertEquals(AttributeDisplayType.TEXT, attributes.get("number").displayType());
  }

  @Test
  void json_settings_and_viewer_metadata_round_trip() {
    JsonMapper mapper = new JsonMapper();
    FeatureTypeSettings catalog = mapper.readValue(
        "{\"attributeSettings\":{\"url\":{\"title\":\"Camera\",\"displayType\":\"video\"}}}",
        FeatureTypeSettings.class);
    FeatureTypeSettings restored = mapper.readValue(mapper.writeValueAsString(catalog), FeatureTypeSettings.class);
    assertEquals(
        AttributeDisplayType.VIDEO,
        restored.getAttributeSettings().get("url").getDisplayType());
    AppLayerSettings overrides =
        mapper.readValue("{\"attributeDisplayOverrides\":{\"url\":\"text\"}}", AppLayerSettings.class);
    assertEquals(
        AttributeDisplayType.TEXT,
        mapper.readValue(mapper.writeValueAsString(overrides), AppLayerSettings.class)
            .getAttributeDisplayOverrides()
            .get("url"));
    ColumnMetadata column =
        new ColumnMetadata().name("url").type(TMAttributeType.STRING).displayType(AttributeDisplayType.VIDEO);
    assertEquals(
        "video",
        mapper.readTree(mapper.writeValueAsString(column))
            .get("displayType")
            .asString());
    assertNull(mapper.readValue("{\"title\":\"Existing\"}", AttributeSettings.class)
        .getDisplayType());
  }

  @Test
  void application_settings_update_removes_overrides_instead_of_merging_stale_values() {
    JsonMapper mapper = new JsonMapper();
    Application app = new Application()
        .setSettings(new AppSettings()
            .putLayerSettingsItem(
                "layer",
                new AppLayerSettings()
                    .putAttributeDisplayOverridesItem("url", AttributeDisplayType.TEXT)
                    .putAttributeDisplayOverridesItem("other", AttributeDisplayType.VIDEO)));
    // Admin PATCH sends the complete settings object. Retain one override and remove the other.
    mapper.readerForUpdating(app)
        .readValue(
            "{\"settings\":{\"layerSettings\":{\"layer\":{\"attributeDisplayOverrides\":{\"other\":\"video\"}}}}}");
    assertEquals(
        Map.of("other", AttributeDisplayType.VIDEO),
        app.getSettings().getLayerSettings().get("layer").getAttributeDisplayOverrides());
    mapper.readerForUpdating(app)
        .readValue("{\"settings\":{\"layerSettings\":{\"layer\":{\"attributeDisplayOverrides\":{}}}}}");
    assertEquals(Map.of(), app.getSettings().getLayerSettings().get("layer").getAttributeDisplayOverrides());
  }
}
