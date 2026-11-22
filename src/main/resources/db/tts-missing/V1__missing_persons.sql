-- Private extension history, on the explicitly configured geodata connection only.
-- PostGIS must already be installed by the database administrator.
CREATE TABLE vermissingen.active_cases (
  id uuid PRIMARY KEY,
  application_id bigint NOT NULL,
  owner_organisation text NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  last_seen_at timestamptz NOT NULL,
  last_seen_geom geometry(Point,4326) NOT NULL,
  age integer CHECK (age BETWEEN 0 AND 120),
  sex text NOT NULL CHECK (sex IN ('unknown','female','male','other','not_specified')),
  speed_kmh double precision CHECK (speed_kmh > 0 AND speed_kmh <= 15),
  water_risk boolean NOT NULL,
  urgent boolean NOT NULL,
  private_intake jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX active_application_idx ON vermissingen.active_cases(application_id, last_seen_at);
CREATE INDEX active_geom_idx ON vermissingen.active_cases USING gist(last_seen_geom);
CREATE TABLE vermissingen.case_organisations (
  case_id uuid NOT NULL REFERENCES vermissingen.active_cases(id) ON DELETE CASCADE,
  organisation_id text NOT NULL,
  PRIMARY KEY(case_id, organisation_id)
);
CREATE TABLE vermissingen.photos (
  case_id uuid PRIMARY KEY REFERENCES vermissingen.active_cases(id) ON DELETE CASCADE,
  jpeg bytea NOT NULL CHECK (octet_length(jpeg) <= 5000000)
);
CREATE TABLE vermissingen.events (
  id uuid PRIMARY KEY,
  case_id uuid NOT NULL REFERENCES vermissingen.active_cases(id) ON DELETE CASCADE,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  kind text NOT NULL CHECK (kind IN ('note','police','handover','found')),
  body text NOT NULL
);
CREATE INDEX event_case_idx ON vermissingen.events(case_id,occurred_at);
-- No FK or original case identifier survives in the statistical domain.
CREATE TABLE vermissingen.closed_stats (
  id uuid PRIMARY KEY,
  application_id bigint NOT NULL,
  organisations text[] NOT NULL,
  age integer,
  sex text NOT NULL,
  last_seen_at timestamptz NOT NULL,
  last_seen_geom geometry(Point,4326),
  found_at timestamptz,
  found_geom geometry(Point,4326),
  outcome text NOT NULL CHECK (outcome IN ('found','transferred','not_found','other')),
  speed_kmh double precision,
  method text NOT NULL DEFAULT 'indicative_buffer',
  environment jsonb NOT NULL DEFAULT '{"weather":"unknown","water":"unknown"}',
  closed_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
CREATE INDEX stats_expiry_idx ON vermissingen.closed_stats(expires_at);
REVOKE ALL ON ALL TABLES IN SCHEMA vermissingen FROM PUBLIC;
