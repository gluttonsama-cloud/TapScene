CREATE TABLE states (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL CHECK(sort_order>=0),
                title TEXT NOT NULL, description TEXT NOT NULL, is_terminal INTEGER NOT NULL CHECK(is_terminal IN (0,1)),
                source_id TEXT NOT NULL, input_asset_id TEXT NOT NULL, frame_pts_us INTEGER NOT NULL CHECK(frame_pts_us>=0),
                time_precision_us INTEGER NOT NULL CHECK(time_precision_us>0), masks_json TEXT NOT NULL,
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,input_asset_id), UNIQUE(project_id,capture_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,input_asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            );
