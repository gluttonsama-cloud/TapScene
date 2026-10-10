package com.tapscene.hosting;

import java.util.*;

/** Wire data. Dates stay ISO-8601 strings; no token is persisted with a task or receipt. */
public final class HostedModels {
    private HostedModels() {}
    public static final class Account {
        public final String accountId, email;
        public Account(String accountId, String email) { this.accountId = accountId; this.email = email; }
    }
    public static final class Session {
        public final Account account;
        public final String expiresAt;
        private final String token;
        public Session(Account account, String expiresAt, String token) {
            this.account = account; this.expiresAt = expiresAt; this.token = token;
        }
        public String bearerToken() { return token; }
        @Override public String toString() { return "Session(redacted)"; }
    }
    public static final class Challenge {
        public final String challengeId, expiresAt;
        public final int resendAfterSeconds;
        public Challenge(String challengeId, String expiresAt, int seconds) {
            this.challengeId = challengeId; this.expiresAt = expiresAt; this.resendAfterSeconds = seconds;
        }
    }
    public static final class Capabilities {
        public final List<String> schemaVersions, policyVersions;
        public final List<Integer> expiryDays;
        public final int defaultExpiryDays;
        public final long packageBytes;
        public Capabilities(List<String> schemas, List<String> policies, List<Integer> days, int defaultDays, long bytes) {
            schemaVersions = Collections.unmodifiableList(schemas); policyVersions = Collections.unmodifiableList(policies);
            expiryDays = Collections.unmodifiableList(days); defaultExpiryDays = defaultDays; packageBytes = bytes;
        }
    }
    public static final class Page<T> {
        public final List<T> items;
        public final String nextCursor;
        public Page(List<T> items, String cursor) { this.items = Collections.unmodifiableList(new ArrayList<>(items)); nextCursor = cursor; }
    }
    public static final class Project {
        public final String serverProjectId, clientProjectId, title;
        public final int activePublicationCount, latestVersionOrdinal;
        public Project(String serverId, String clientId, String title, int active, int latest) {
            serverProjectId = serverId; clientProjectId = clientId; this.title = title;
            activePublicationCount = active; latestVersionOrdinal = latest;
        }
    }
    public static final class Publication {
        public final String publicationId, serverProjectId, releaseId, contentDigest, title, createdAt, expiresAt, status, shareUrl, revokedAt;
        public final int versionOrdinal;
        public Publication(String id, String project, String release, int version, String digest, String title,
                String created, String expires, String status, String url, String revoked) {
            publicationId = id; serverProjectId = project; releaseId = release; versionOrdinal = version;
            contentDigest = digest; this.title = title; createdAt = created; expiresAt = expires;
            this.status = status; shareUrl = url; revokedAt = revoked;
        }
        @Override public String toString() { return "Publication(" + publicationId + ", " + status + ")"; }
    }
    public static final class Revocation {
        public final String publicationId, status, revokedAt, serverConfirmedAt;
        public Revocation(String id, String revoked, String confirmed) {
            publicationId = id; status = "revoked"; revokedAt = revoked; serverConfirmedAt = confirmed;
        }
    }
    public static final class Upload {
        public final String uploadId, releaseId, state, reservedUntil, errorCode;
        public final List<String> missingAssetIds;
        public final Publication publication;
        public Upload(String id, String release, String state, String reserved, List<String> missing, String error, Publication publication) {
            uploadId = id; releaseId = release; this.state = state; reservedUntil = reserved;
            missingAssetIds = Collections.unmodifiableList(missing); errorCode = error; this.publication = publication;
        }
    }
    public static final class Commit {
        public final String uploadId, statusUrl;
        public final int retryAfterSeconds;
        public final Publication publication;
        public Commit(String id, String statusUrl, int retry, Publication publication) {
            uploadId = id; this.statusUrl = statusUrl; retryAfterSeconds = retry; this.publication = publication;
        }
    }
    public static final class Asset {
        public final String id, path, mime, sha256;
        public final long byteLength;
        public Asset(String id, String path, String mime, String hash, long length) {
            this.id = id; this.path = path; this.mime = mime; sha256 = hash; byteLength = length;
        }
    }
    /** Immutable durable snapshot. Mutations are serialized and fsynced by HostedStore. */
    public static final class Task {
        public final String taskId, endpoint, accountId, localProjectId, releaseId, contentDigest, title;
        public final long projectRevision, byteLength;
        public final int expiryDays, assetCount;
        public final String canonicalScene, fileListDigest, createKey, commitKey, createRequest, commitRequest;
        public final String serverProjectId, uploadId, state, errorCode;
        public final boolean cancelRequested;
        public final Publication publication;
        public Task(String taskId, String endpoint, String accountId, String localProjectId, String releaseId,
                String digest, String title, long revision, long bytes, int days, int assetCount, String scene,
                String fileListDigest, String createKey, String commitKey, String createRequest, String commitRequest,
                String serverProjectId, String uploadId, String state, boolean cancel, String error, Publication publication) {
            this.taskId = taskId; this.endpoint = endpoint; this.accountId = accountId; this.localProjectId = localProjectId;
            this.releaseId = releaseId; contentDigest = digest; this.title = title; projectRevision = revision;
            byteLength = bytes; expiryDays = days; this.assetCount = assetCount; canonicalScene = scene;
            this.fileListDigest = fileListDigest; this.createKey = createKey; this.commitKey = commitKey;
            this.createRequest = createRequest; this.commitRequest = commitRequest; this.serverProjectId = serverProjectId;
            this.uploadId = uploadId; this.state = state; cancelRequested = cancel; errorCode = error; this.publication = publication;
        }
        @Override public String toString() { return "HostedTask(" + taskId + ", " + state + ")"; }
    }
}
