-- Frozen shipped v7 image-source tables from c928f73.
CREATE TABLE image_sources (
                project_id TEXT NOT NULL, source_id TEXT NOT NULL UNIQUE, mime TEXT NOT NULL CHECK(mime IN ('image/png','image/jpeg')),
                source_json TEXT NOT NULL, PRIMARY KEY(project_id,source_id),
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            );
CREATE TABLE image_source_imports(asset_id TEXT PRIMARY KEY NOT NULL, source_id TEXT UNIQUE NOT NULL, mime TEXT NOT NULL);
CREATE TABLE image_source_cleanup(project_id TEXT NOT NULL, source_id TEXT PRIMARY KEY NOT NULL, mime TEXT NOT NULL);
