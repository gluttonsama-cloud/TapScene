CREATE TABLE accounts (
 account_id uuid PRIMARY KEY, email text NOT NULL, email_normalized text NOT NULL UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(), disabled_at timestamptz
);
CREATE TABLE auth_challenges (
 challenge_id uuid PRIMARY KEY, email_normalized text NOT NULL, code_mac bytea NOT NULL,
 attempt_count smallint NOT NULL DEFAULT 0 CHECK(attempt_count >= 0), max_attempts smallint NOT NULL DEFAULT 5,
 expires_at timestamptz NOT NULL, consumed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX challenges_email ON auth_challenges(email_normalized, created_at);
CREATE TABLE sessions (
 session_id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES accounts, token_hash bytea UNIQUE NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz
);
CREATE TABLE hosted_projects (
 project_id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES accounts, client_project_id uuid NOT NULL,
 title text NOT NULL, next_version integer NOT NULL DEFAULT 1 CHECK(next_version > 0), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_id,client_project_id), UNIQUE(project_id,owner_id)
);
CREATE TABLE publication_uploads (
 upload_id uuid PRIMARY KEY, project_id uuid NOT NULL, owner_id uuid NOT NULL, release_id uuid NOT NULL,
 scene_json jsonb NOT NULL, content_digest bytea NOT NULL, expiry_days smallint NOT NULL CHECK(expiry_days IN (1,7,30)),
 state text NOT NULL CHECK(state IN ('receiving','validating','committed','cancelled','rejected','expired')),
 idempotency_key uuid NOT NULL, request_digest bytea NOT NULL, commit_key uuid, commit_request_digest bytea,
 reserved_until timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), commit_requested_at timestamptz,
 committed_at timestamptz, lease_token uuid, lease_until timestamptz, attempt integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz, error_code text,
 FOREIGN KEY(project_id,owner_id) REFERENCES hosted_projects(project_id,owner_id), UNIQUE(owner_id,idempotency_key)
);
CREATE UNIQUE INDEX pending_release ON publication_uploads(owner_id, release_id) WHERE state IN ('receiving','validating');
CREATE INDEX pending_work ON publication_uploads(state,next_attempt_at,lease_until);
CREATE TABLE publication_assets (
 upload_id uuid NOT NULL REFERENCES publication_uploads, asset_id uuid NOT NULL, relative_path text NOT NULL,
 role text NOT NULL, mime text NOT NULL, byte_length bigint NOT NULL CHECK(byte_length > 0), sha256 bytea NOT NULL,
 width integer NOT NULL, height integer NOT NULL, duration_ms integer, staging_key text, final_key text UNIQUE,
 state text NOT NULL CHECK(state IN ('declared','received','verified')), received_at timestamptz, verified_at timestamptz,
 PRIMARY KEY(upload_id,asset_id), UNIQUE(upload_id,relative_path)
);
CREATE TABLE releases (
 release_id uuid PRIMARY KEY, project_id uuid NOT NULL, owner_id uuid NOT NULL, upload_id uuid NOT NULL UNIQUE REFERENCES publication_uploads,
 version_ordinal integer NOT NULL, schema_version text NOT NULL, policy_version text NOT NULL,
 scene_json jsonb NOT NULL, content_digest bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(project_id,owner_id) REFERENCES hosted_projects(project_id,owner_id), UNIQUE(project_id,version_ordinal)
);
CREATE TABLE shares (
 share_id uuid PRIMARY KEY, release_id uuid NOT NULL UNIQUE REFERENCES releases,
 token_hash bytea NOT NULL UNIQUE, token_ciphertext bytea NOT NULL, token_key_version text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz
);
CREATE TABLE share_revocations (
 sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, share_id uuid NOT NULL UNIQUE, revoked_at timestamptz NOT NULL
);
CREATE FUNCTION immutable_release() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'published release is immutable'; END $$;
CREATE TRIGGER immutable_release BEFORE UPDATE OR DELETE ON releases FOR EACH ROW EXECUTE FUNCTION immutable_release();
CREATE FUNCTION immutable_share() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - 'revoked_at') IS DISTINCT FROM (to_jsonb(OLD) - 'revoked_at')
    OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
  RAISE EXCEPTION 'share identity, expiry, and revocation are immutable'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER immutable_share BEFORE UPDATE OR DELETE ON shares FOR EACH ROW EXECUTE FUNCTION immutable_share();
CREATE TRIGGER immutable_revocation BEFORE UPDATE OR DELETE ON share_revocations FOR EACH ROW EXECUTE FUNCTION immutable_release();
CREATE FUNCTION immutable_asset() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF EXISTS(SELECT 1 FROM releases WHERE upload_id = OLD.upload_id) THEN
  RAISE EXCEPTION 'published assets are immutable'; END IF;
 IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW; END $$;
CREATE TRIGGER immutable_asset BEFORE UPDATE OR DELETE ON publication_assets FOR EACH ROW EXECUTE FUNCTION immutable_asset();
