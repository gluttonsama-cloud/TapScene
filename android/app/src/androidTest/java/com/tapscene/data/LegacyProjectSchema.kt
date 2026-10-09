package com.tapscene.data

/** Exact historical DDL from db09a6b. Never construct legacy fixtures by downgrading user_version. */
internal object LegacyProjectSchema {
    val statements = listOf(
        """CREATE TABLE projects (
                project_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, goal TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, draft_revision INTEGER NOT NULL CHECK(draft_revision>0),
                start_state_id TEXT,
                FOREIGN KEY(project_id,start_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE sources (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL, source_json TEXT NOT NULL,
                PRIMARY KEY(project_id,source_id), FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE local_assets (
                asset_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL, relative_path TEXT UNIQUE NOT NULL,
                sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL CHECK(byte_length>0),
                width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0),
                UNIQUE(project_id,asset_id), FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE states (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL CHECK(sort_order>=0),
                title TEXT NOT NULL, description TEXT NOT NULL, is_terminal INTEGER NOT NULL CHECK(is_terminal IN (0,1)),
                source_id TEXT NOT NULL, input_asset_id TEXT NOT NULL, frame_pts_us INTEGER NOT NULL CHECK(frame_pts_us>=0),
                time_precision_us INTEGER NOT NULL CHECK(time_precision_us>0), masks_json TEXT NOT NULL,
                PRIMARY KEY(project_id,state_id), UNIQUE(project_id,input_asset_id), UNIQUE(project_id,capture_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,input_asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE hotspots (
                project_id TEXT NOT NULL, hotspot_id TEXT NOT NULL, state_id TEXT NOT NULL, label TEXT NOT NULL,
                rect_left REAL NOT NULL CHECK(rect_left>=0 AND rect_left<1),
                rect_top REAL NOT NULL CHECK(rect_top>=0 AND rect_top<1),
                rect_right REAL NOT NULL CHECK(rect_right>rect_left AND rect_right<=1),
                rect_bottom REAL NOT NULL CHECK(rect_bottom>rect_top AND rect_bottom<=1),
                PRIMARY KEY(project_id,hotspot_id), UNIQUE(project_id,hotspot_id,state_id),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE edges (
                project_id TEXT NOT NULL, edge_id TEXT NOT NULL, hotspot_id TEXT NOT NULL, from_state_id TEXT NOT NULL,
                to_state_id TEXT, end_label TEXT,
                PRIMARY KEY(project_id,edge_id), UNIQUE(project_id,hotspot_id),
                CHECK((to_state_id IS NOT NULL AND end_label IS NULL) OR (to_state_id IS NULL AND end_label IS NOT NULL AND length(trim(end_label))>0)),
                FOREIGN KEY(project_id,hotspot_id,from_state_id) REFERENCES hotspots(project_id,hotspot_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,from_state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,to_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE next_actions (
                project_id TEXT NOT NULL, action_id TEXT NOT NULL, from_state_id TEXT NOT NULL,
                label TEXT NOT NULL CHECK(length(trim(label))>0), to_state_id TEXT,
                PRIMARY KEY(project_id,action_id), UNIQUE(project_id,from_state_id),
                FOREIGN KEY(project_id,from_state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,to_state_id) REFERENCES states(project_id,state_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE edge_transitions (
                project_id TEXT NOT NULL, edge_id TEXT NOT NULL, asset_id TEXT NOT NULL,
                source_id TEXT NOT NULL, start_us INTEGER NOT NULL CHECK(start_us>=0),
                end_us INTEGER NOT NULL CHECK(end_us>start_us AND end_us-start_us<=10000000),
                duration_us INTEGER NOT NULL CHECK(duration_us>0 AND duration_us<=10000000),
                masks_json TEXT NOT NULL, review_id TEXT NOT NULL CHECK(length(review_id)>0),
                PRIMARY KEY(project_id,edge_id), UNIQUE(project_id,asset_id), UNIQUE(project_id,review_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(project_id,source_id) REFERENCES sources(project_id,source_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE regions (
                project_id TEXT NOT NULL, region_id TEXT NOT NULL, state_id TEXT NOT NULL,
                base_asset_id TEXT NOT NULL, base_sha256 TEXT NOT NULL, name TEXT NOT NULL, group_name TEXT,
                x_px INTEGER NOT NULL CHECK(x_px>=0), y_px INTEGER NOT NULL CHECK(y_px>=0),
                width_px INTEGER NOT NULL CHECK(width_px>0), height_px INTEGER NOT NULL CHECK(height_px>0),
                source_width INTEGER NOT NULL CHECK(source_width>0), source_height INTEGER NOT NULL CHECK(source_height>0),
                z_index INTEGER NOT NULL CHECK(z_index BETWEEN -10000 AND 10000),
                anchor_x REAL NOT NULL CHECK(anchor_x BETWEEN 0 AND 1), anchor_y REAL NOT NULL CHECK(anchor_y BETWEEN 0 AND 1),
                asset_id TEXT, reviewed_at INTEGER,
                PRIMARY KEY(project_id,region_id), UNIQUE(project_id,asset_id),
                CHECK(x_px+width_px<=source_width AND y_px+height_px<=source_height),
                CHECK(reviewed_at IS NULL OR asset_id IS NOT NULL),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id,asset_id) REFERENCES local_assets(project_id,asset_id) DEFERRABLE INITIALLY DEFERRED
            )""",
        """CREATE TABLE editor_drafts (
                project_id TEXT NOT NULL, state_id TEXT NOT NULL, draft_json TEXT NOT NULL,
                PRIMARY KEY(project_id,state_id),
                CHECK(length(CAST(draft_json AS BLOB)) BETWEEN 1 AND 262144),
                FOREIGN KEY(project_id,state_id) REFERENCES states(project_id,state_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE editor_draft_sessions (
                project_id TEXT PRIMARY KEY NOT NULL, generation INTEGER NOT NULL CHECK(generation>0),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )""",
        """CREATE TABLE asset_cleanup(project_id TEXT NOT NULL, relative_path TEXT PRIMARY KEY NOT NULL)""",
        """CREATE TABLE asset_imports(project_id TEXT NOT NULL, asset_id TEXT PRIMARY KEY NOT NULL)""",
        """CREATE INDEX projects_updated ON projects(updated_at)""",
        """CREATE INDEX states_order ON states(project_id,sort_order)""",
        """CREATE INDEX states_source ON states(source_id)""",
        """CREATE INDEX hotspots_state ON hotspots(project_id,state_id)""",
        """CREATE INDEX edges_target ON edges(project_id,to_state_id)""",
        """CREATE INDEX next_actions_target ON next_actions(project_id,to_state_id)""",
        """CREATE INDEX edge_transitions_source ON edge_transitions(source_id)""",
        """CREATE TABLE transition_imports(project_id TEXT NOT NULL, asset_id TEXT PRIMARY KEY NOT NULL)""",
        """CREATE INDEX regions_state ON regions(project_id,state_id)"""
    )
    private val statesV6 = """CREATE TABLE states (
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
            )"""
    fun statements(version: Int) = statements.map { if (version >= 6 && it.startsWith("CREATE TABLE states (")) statesV6 else it }.filterNot { sql ->
        (version < 2 && sql.contains("next_actions")) ||
        (version < 3 && (sql.contains("edge_transitions") || sql.contains("transition_imports"))) ||
        (version < 4 && sql.contains("regions")) ||
        (version < 5 && sql.contains("editor_draft"))
    }
}
