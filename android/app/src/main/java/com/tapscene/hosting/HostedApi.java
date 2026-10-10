package com.tapscene.hosting;

import static com.tapscene.hosting.HostedModels.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Development-only transport. The destination is never read from preferences or user input. */
public class HostedApi {
    public static final String ENDPOINT = "http://127.0.0.1:4173";
    private static final int MAX_RESPONSE = 1024 * 1024;
    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int statusCode, retryAfterSeconds;
        public final String code;
        public ApiException(int status, String code, int retry) {
            super(code); statusCode = status; this.code = code; retryAfterSeconds = retry;
        }
    }
    public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private final ConnectionFactory factory;
    public HostedApi() { this(url -> (HttpURLConnection) url.openConnection(Proxy.NO_PROXY)); }
    /** A test seam for fake connections; endpoint and URL validation are not replaceable. */
    public HostedApi(ConnectionFactory factory) { this.factory = Objects.requireNonNull(factory); }

    public Capabilities capabilities() throws IOException {
        Map<String,Object> m = request("GET", "/api/v1/capabilities", null, null, null).body;
        require("local".equals(string(m,"mode")) && "synthetic-mailbox-only".equals(string(m,"authentication")), "UNSUPPORTED_SERVER");
        List<Integer> days = new ArrayList<>(); for (Object o : list(m,"expiryDays")) days.add(integer(o));
        return new Capabilities(strings(m,"schemaVersions"), strings(m,"policyVersions"), days,
            integer(m.get("defaultExpiryDays")), number(object(m.get("limits")).get("packageBytes")));
    }
    public Challenge requestChallenge(String email) throws IOException {
        syntheticEmail(email);
        Map<String,Object> m = request("POST", "/api/v1/auth/challenges", null,
            json(map("email",email)), null).body;
        return new Challenge(id(string(m,"challengeId")), iso(string(m,"expiresAt")), positive(m,"resendAfterSeconds"));
    }
    public Session createSession(String challengeId, String code) throws IOException {
        id(challengeId); require(code != null && code.matches("[0-9]{6}"), "INVALID_CODE");
        Map<String,Object> m = request("POST", "/api/v1/auth/sessions", null,
            json(map("challengeId",challengeId,"code",code)), null).body;
        Map<String,Object> a = object(m.get("account"));
        String token = string(m,"sessionToken"); require(token.matches("[A-Za-z0-9_-]{43}"), "INVALID_SESSION");
        return new Session(account(a), iso(string(m,"expiresAt")), token);
    }
    public Account me(Session session) throws IOException {
        Map<String,Object> m = request("GET", "/api/v1/me", session, null, null).body;
        iso(string(m,"sessionExpiresAt")); Account account = account(m);
        require(account.accountId.equals(session.account.accountId), "ACCOUNT_MISMATCH"); return account;
    }
    public void revokeSession(Session session) throws IOException {
        request("DELETE", "/api/v1/auth/sessions/current", session, null, null);
    }
    public Project createProject(Session session, String localProjectId, String title) throws IOException {
        id(localProjectId); require(title != null && !title.trim().isEmpty() && title.length() <= 240, "INVALID_TITLE");
        Project p = project(request("POST", "/api/v1/projects", session,
            json(map("clientProjectId",localProjectId,"title",title)), null).body);
        require(localProjectId.equals(p.clientProjectId), "PROJECT_MISMATCH"); return p;
    }
    public Page<Project> projects(Session session, String cursor) throws IOException {
        Map<String,Object> m = request("GET", "/api/v1/projects" + query(cursor), session, null, null).body;
        List<Project> items = new ArrayList<>(); for (Object o : list(m,"items")) items.add(project(object(o)));
        return new Page<>(items, cursor(m));
    }
    public Page<Publication> publications(Session session, String projectId, String cursor) throws IOException {
        Map<String,Object> m = request("GET", "/api/v1/projects/" + id(projectId) + "/publications" + query(cursor), session, null, null).body;
        List<Publication> items = new ArrayList<>();
        for (Object o : list(m,"items")) {
            Publication p = publication(object(o)); require(p.serverProjectId.equals(projectId), "PUBLICATION_MISMATCH"); items.add(p);
        }
        return new Page<>(items, cursor(m));
    }
    public Publication publication(Session session, String publicationId) throws IOException {
        Publication p = publication(request("GET", "/api/v1/publications/" + id(publicationId), session, null, null).body);
        require(p.publicationId.equals(publicationId), "PUBLICATION_MISMATCH"); return p;
    }
    public Upload createUpload(Session session, Task task) throws IOException {
        require(task.createRequest != null, "REQUEST_NOT_PREPARED");
        return upload(request("POST", "/api/v1/publication-uploads", session,
            task.createRequest.getBytes(StandardCharsets.UTF_8), id(task.createKey)).body);
    }
    public Upload uploadStatus(Session session, String uploadId) throws IOException {
        Upload u = upload(request("GET", "/api/v1/publication-uploads/" + id(uploadId), session, null, null).body);
        require(u.uploadId.equals(uploadId), "UPLOAD_MISMATCH"); return u;
    }
    public Upload cancelUpload(Session session, String uploadId) throws IOException {
        Upload u = upload(request("DELETE", "/api/v1/publication-uploads/" + id(uploadId), session, null, null).body);
        require(u.uploadId.equals(uploadId), "UPLOAD_MISMATCH"); return u;
    }
    public void putAsset(Session session, String uploadId, Asset asset, File file) throws IOException {
        require(asset.mime.equals("image/png") || asset.mime.equals("video/mp4"), "ASSET_TYPE_MISMATCH");
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() == asset.byteLength,
            "SNAPSHOT_ASSET_MISMATCH");
        require(sha(file).equals(asset.sha256), "SNAPSHOT_ASSET_MISMATCH");
        Map<String,Object> m = exchange("PUT", "/api/v1/publication-uploads/" + id(uploadId) + "/assets/" + id(asset.id),
            session, null, null, asset, file).body;
        require(asset.id.equals(string(m,"assetId")) && "received".equals(string(m,"state")) &&
            asset.byteLength == number(m.get("byteLength")) && asset.sha256.equals(string(m,"sha256")), "ASSET_RECEIPT_MISMATCH");
    }
    public Commit commit(Session session, Task task) throws IOException {
        require(task.uploadId != null, "UPLOAD_NOT_PREPARED");
        Response response = request("POST", "/api/v1/publication-uploads/" + id(task.uploadId) + "/commit", session,
            task.commitRequest.getBytes(StandardCharsets.UTF_8), id(task.commitKey));
        if (response.status == 202) {
            Map<String,Object> m = response.body;
            require(task.uploadId.equals(string(m,"uploadId")) && "validating".equals(string(m,"state")), "UPLOAD_MISMATCH");
            String status = string(m,"statusUrl"); validateStatusUrl(status, task.uploadId);
            return new Commit(task.uploadId, status, Math.min(60, Math.max(1, positive(m,"retryAfterSeconds"))), null);
        }
        return new Commit(task.uploadId, null, 0, publication(response.body));
    }
    public Revocation revoke(Session session, String publicationId) throws IOException {
        Map<String,Object> m = request("POST", "/api/v1/publications/" + id(publicationId) + "/revoke", session, null, null).body;
        require(publicationId.equals(string(m,"publicationId")) && "revoked".equals(string(m,"status")), "REVOCATION_MISMATCH");
        return new Revocation(publicationId, iso(string(m,"revokedAt")), iso(string(m,"serverConfirmedAt")));
    }
    public static String validateShareUrl(String value) {
        URI uri = validatedUrl(value); require(uri.getRawQuery() == null &&
            uri.getRawPath().matches("/s/[A-Za-z0-9_-]{43}"), "UNSAFE_SHARE_URL"); return uri.toString();
    }
    public static String validateStatusUrl(String value, String uploadId) {
        URI uri = validatedUrl(value); require(uri.getRawQuery() == null &&
            uri.getRawPath().equals("/api/v1/publication-uploads/" + id(uploadId)), "UNSAFE_STATUS_URL"); return uri.toString();
    }
    static URI validatedUrl(String value) {
        try {
            URI raw = new URI(value);
            require(raw.getRawUserInfo() == null && raw.getRawFragment() == null && !value.contains("\\") &&
                (raw.isAbsolute() || value.startsWith("/") && !value.startsWith("//")), "UNSAFE_URL");
            URI uri = URI.create(ENDPOINT).resolve(raw);
            require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 4173 &&
                uri.getRawAuthority().equals("127.0.0.1:4173") && uri.getRawUserInfo() == null &&
                uri.getRawFragment() == null && uri.normalize().equals(uri), "UNSAFE_URL"); return uri;
        } catch (URISyntaxException | NullPointerException e) { throw new IllegalArgumentException("UNSAFE_URL"); }
    }
    private Response request(String method, String path, Session session, byte[] bytes, String key) throws IOException {
        return exchange(method, path, session, bytes, key, null, null);
    }
    private Response exchange(String method, String path, Session session, byte[] bytes, String key, Asset asset, File file) throws IOException {
        URI uri = validatedUrl(path);
        require(uri.getRawPath().startsWith("/api/v1/"), "UNSAFE_API_PATH");
        HttpURLConnection connection = factory.open(uri.toURL());
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
            connection.setUseCaches(false); connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
            if (session != null) {
                require(session.bearerToken().matches("[A-Za-z0-9_-]{43}"), "INVALID_SESSION");
                connection.setRequestProperty("Authorization", "Bearer " + session.bearerToken());
            }
            if (key != null) connection.setRequestProperty("Idempotency-Key", id(key));
            if (bytes != null || asset != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", asset == null ? "application/json" : asset.mime);
                connection.setFixedLengthStreamingMode(asset == null ? bytes.length : asset.byteLength);
                if (asset != null) connection.setRequestProperty("X-Content-SHA256", asset.sha256);
                try (OutputStream out = connection.getOutputStream()) {
                    if (asset == null) out.write(bytes);
                    else try (InputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[64 * 1024]; long sent = 0; int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TRANSFER_INTERRUPTED");
                            sent += n; require(sent <= asset.byteLength, "SNAPSHOT_ASSET_MISMATCH"); out.write(buffer,0,n);
                        }
                        require(sent == asset.byteLength, "SNAPSHOT_ASSET_MISMATCH");
                    }
                }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new ApiException(status, "REDIRECT_REFUSED", 0);
            byte[] body = readBounded(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) {
                String code = "HTTP_ERROR";
                try { code = safeCode(string(object(object(HostedJson.parse(body,MAX_RESPONSE)).get("error")),"code")); }
                catch (RuntimeException ignored) { /* Never expose arbitrary response text. */ }
                int retry = 0;
                try { retry = Math.min(3600, Math.max(0, Integer.parseInt(connection.getHeaderField("Retry-After")))); }
                catch (RuntimeException ignored) {}
                throw new ApiException(status, code, retry);
            }
            if (status == 204) return new Response(status, Collections.emptyMap());
            try { return new Response(status, object(HostedJson.parse(body,MAX_RESPONSE))); }
            catch (RuntimeException e) { throw new ApiException(status, "INVALID_RESPONSE", 0); }
        } finally { connection.disconnect(); }
    }
    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > MAX_RESPONSE) throw new IOException("RESPONSE_TOO_LARGE"); out.write(b,0,n); }
            return out.toByteArray();
        }
    }
    private static final class Response {
        final int status; final Map<String,Object> body;
        Response(int status, Map<String,Object> body) { this.status = status; this.body = body; }
    }
    static Account account(Map<String,Object> m) { String email = string(m,"email"); syntheticEmail(email); return new Account(id(string(m,"accountId")),email); }
    static Project project(Map<String,Object> m) { return new Project(id(string(m,"serverProjectId")),id(string(m,"clientProjectId")),string(m,"title"),
        m.containsKey("activePublicationCount") ? positive(m,"activePublicationCount") : 0,
        m.containsKey("latestVersionOrdinal") ? positive(m,"latestVersionOrdinal") : 0); }
    static Publication publication(Map<String,Object> m) {
        String status = string(m,"status"); require(Arrays.asList("active","revoked","expired").contains(status), "UNKNOWN_PUBLICATION_STATE");
        String revoked = nullableString(m,"revokedAt"); if (revoked != null) iso(revoked);
        require(!status.equals("revoked") || revoked != null, "INVALID_PUBLICATION");
        int ordinal = positive(m,"versionOrdinal"); require(ordinal > 0, "INVALID_PUBLICATION");
        return new Publication(id(string(m,"publicationId")),id(string(m,"serverProjectId")),id(string(m,"releaseId")),ordinal,
            digest(string(m,"contentDigest")),string(m,"title"),iso(string(m,"createdAt")),iso(string(m,"expiresAt")),status,
            validateShareUrl(string(m,"shareUrl")),revoked);
    }
    static Map<String,Object> publicationMap(Publication p) {
        return map("publicationId",p.publicationId,"serverProjectId",p.serverProjectId,"releaseId",p.releaseId,
            "versionOrdinal",p.versionOrdinal,"contentDigest",p.contentDigest,"title",p.title,"createdAt",p.createdAt,
            "expiresAt",p.expiresAt,"status",p.status,"shareUrl",p.shareUrl,"revokedAt",p.revokedAt);
    }
    static Upload upload(Map<String,Object> m) {
        String release = nullableString(m,"releaseId"); if (release != null) id(release);
        String reserved = nullableString(m,"reservedUntil"); if (reserved != null) iso(reserved);
        List<String> missing = m.containsKey("missingAssetIds") ? strings(m,"missingAssetIds") : new ArrayList<>();
        for (String value : missing) id(value);
        String error = m.get("error") == null ? null : safeCode(string(object(m.get("error")),"code"));
        return new Upload(id(string(m,"uploadId")),release,string(m,"state"),reserved,missing,error,
            m.get("publication") == null ? null : publication(object(m.get("publication"))));
    }
    static String query(String cursor) { return "?limit=20" + (cursor == null ? "" : "&cursor=" + id(cursor)); }
    static String cursor(Map<String,Object> m) { String value = nullableString(m,"nextCursor"); return value == null ? null : id(value); }
    public static String syntheticEmail(String value) {
        require(value != null && value.length() <= 254 && value.matches("[a-zA-Z0-9.!#$%&'*+\\-/=?^_`{|}~]+@example\\.test"), "SYNTHETIC_EMAIL_REQUIRED"); return value;
    }
    public static String id(String value) { require(value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "INVALID_ID"); return value; }
    static String digest(String value) { require(value != null && value.matches("[0-9a-f]{64}"), "INVALID_DIGEST"); return value; }
    static String iso(String value) { try { Instant.parse(value); return value; } catch (RuntimeException e) { throw new IllegalArgumentException("INVALID_TIMESTAMP"); } }
    static String safeCode(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : "UNKNOWN_ERROR"; }
    static int positive(Map<String,Object> m, String key) { int n = integer(m.get(key)); require(n >= 0, "INVALID_RESPONSE"); return n; }
    static int integer(Object o) { long n = number(o); require(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "INVALID_NUMBER"); return (int)n; }
    static long number(Object o) { require(o instanceof Number, "INVALID_NUMBER"); try { return new java.math.BigDecimal(o.toString()).longValueExact(); } catch (ArithmeticException e) { throw new IllegalArgumentException("INVALID_NUMBER"); } }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object o) { require(o instanceof Map, "INVALID_OBJECT"); return (Map<String,Object>)o; }
    @SuppressWarnings("unchecked") static List<Object> list(Map<String,Object> m, String key) { require(m.get(key) instanceof List, "INVALID_ARRAY"); return (List<Object>)m.get(key); }
    static String string(Map<String,Object> m, String key) { require(m.get(key) instanceof String, "INVALID_STRING"); return (String)m.get(key); }
    static String nullableString(Map<String,Object> m, String key) { return m.get(key) == null ? null : string(m,key); }
    static List<String> strings(Map<String,Object> m, String key) { List<String> out = new ArrayList<>(); for (Object o : list(m,key)) { require(o instanceof String, "INVALID_STRING"); out.add((String)o); } return out; }
    static Map<String,Object> map(Object... args) { Map<String,Object> out = new LinkedHashMap<>(); for (int i=0;i<args.length;i+=2) out.put((String)args[i],args[i+1]); return out; }
    static byte[] json(Object value) { return HostedJson.canonical(value); }
    static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
    public static String sha(File file) throws IOException {
        try { MessageDigest d = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) { byte[] b=new byte[65536]; int n; while ((n=in.read(b))!=-1) d.update(b,0,n); }
            return hex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String sha(byte[] bytes) { try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT,"%02x",b & 255)); return s.toString(); }
}
