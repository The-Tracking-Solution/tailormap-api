-- Local account foreign keys prevent preferences surviving deletion/recreation.
CREATE TABLE IF NOT EXISTS tts_start.user_start_position (
    application_id bigint NOT NULL REFERENCES public.application(id) ON DELETE CASCADE,
    username varchar(255) NOT NULL REFERENCES public.users(username) ON DELETE CASCADE,
    crs varchar(255) NOT NULL,
    center_x double precision,
    center_y double precision,
    zoom double precision,
    may_edit boolean NOT NULL DEFAULT false,
    extent_minx double precision,
    extent_miny double precision,
    extent_maxx double precision,
    extent_maxy double precision,
    PRIMARY KEY (application_id, username),
    CHECK ((extent_minx IS NULL AND extent_miny IS NULL AND extent_maxx IS NULL AND extent_maxy IS NULL) OR
    (extent_minx IS NOT NULL AND extent_miny IS NOT NULL AND extent_maxx IS NOT NULL AND extent_maxy IS NOT NULL
     AND extent_minx > '-Infinity'::float8 AND extent_maxx < 'Infinity'::float8
     AND extent_miny > '-Infinity'::float8 AND extent_maxy < 'Infinity'::float8
     AND extent_minx < extent_maxx AND extent_miny < extent_maxy)
),
    CHECK ((center_x IS NULL AND center_y IS NULL AND zoom IS NULL) OR
           (center_x IS NOT NULL AND center_y IS NOT NULL AND zoom IS NOT NULL
            AND center_x > '-Infinity'::float8 AND center_x < 'Infinity'::float8
            AND center_y > '-Infinity'::float8 AND center_y < 'Infinity'::float8
            AND zoom >= 0 AND zoom <= 28))
);

