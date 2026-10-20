# Attribute video presentation

The viewer/Admin feature uses the optional shared `AttributeDisplayType` enum:
`text` or `video`. It describes feature-info presentation, not the storage type.

- Catalog: `FeatureTypeSettings.attributeSettings[name].displayType`.
- Application-layer override: `AppLayerSettings.attributeDisplayOverrides[name]`.
- Viewer feature response: `ColumnMetadata.displayType`.

`TMFeatureTypeHelper.getConfiguredAttributes` resolves presentation for visible
attributes. An explicit layer override wins over the catalog value. A missing
key inherits; removing a key restores inheritance and follows future catalog
changes. An unset catalog value resolves to text. Non-string types always use
text; presentation overrides cannot expose catalog- or layer-hidden fields or
add unknown fields. Resolving settings does not modify the shared catalog.

`FeaturesController` includes the resolved presentation in its existing column
metadata. The API never loads media URLs, proxies videos, converts protocols or
relaxes feature visibility/authorization. The viewer separately enforces the
application's existing `showEmbeddedVideos` master switch and validates supported
video-provider URLs before loading a player after a user action.

Settings are optional members of the existing JSONB settings. No schema/Flyway
or source-table migration is needed. Existing configuration defaults to text;
the new viewer also treats missing metadata from older APIs as text. Admin and
viewer must be deployed with a compatible API to persist/consume the new fields.
No automatic migration of legacy TTS POC URL detection is performed.

The initial provider is YouTube. Other provider adapters are separate follow-up
work; arbitrary iframe URLs are not accepted. Keep this feature independent of
TTS issue #4 and other custom changes for a possible standalone upstream proposal.

Unit tests exercise inheritance, both override directions, removing overrides,
shared-catalog immutability, hidden/unknown/non-string attributes, JSON round trips
and metadata enum serialization. Actual Admin persistence and stream/browser
acceptance still require a dev environment with the matching viewer.
