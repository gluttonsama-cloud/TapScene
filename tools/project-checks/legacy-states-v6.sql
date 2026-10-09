CREATE TABLE states (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL CHECK(sort_order>=0),
                title TEXT NOT NULL, description TEXT NOT NULL, is_terminal INTEGER NOT NULL CHECK(is_terminal IN (0,1)),
                source_id TEXT, input_asset_id TEXT NOT NULL, frame_pts_us INTEGER CHECK(frame_pts_us>=0),
                time_precision_us INTEGER CHECK(time_precision_us>0), masks_json TEXT NOT NULL,
                origin_kind TEXT NOT NULL CHECK(origin_kind IN ('videoFrame','image')),
                base_asset_id TEXT, base_sha256 TEXT, base_revision INTEGER, base_width INTEGER, base_height INTEGER,
                CHECK((origin_kind='videoFrame' AND source_id IS NOT NULL AND frame_pts_us IS NOT NULL AND time_precision_us IS NOT NULL
                    AND base_asset_id IS NULL AND base_sha256 IS NULL AND base_revision IS NULL AND base_width IS NULL AND base_height IS NULL)
                    OR (origin_kind='image' AND source_id IS NULL AND frame_pts_us IS NULL AND time_precision_us IS NULL
                    AND base_asset_id IS NOT NULL AND length(base_asset_id)>0 AND base_sha256 IS NOT NULL
                    AND length(base_sha256)=64 AND base_sha256 NOT GLOB '*[^0-9a-f]*'
                    AND base_revision IS NOT NULL AND base_revision>0 AND base_width IS NOT NULL AND base_width>0
                    AND base_height IS NOT NULL AND base_height>0 AND base_width*base_height<=12000000)),
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,input_asset_id), UNIQUE(project_id,capture_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,input_asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            );
