package com.tapscene.hosting;

import static com.tapscene.hosting.HostedModels.*;
import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Host only: uses the production Java API, task journal and engine. Never reads a real mailbox. */
public final class HostedHttpIntegration {
    private HostedHttpIntegration() { }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile(), fixture = new File(args[1]).getCanonicalFile();
        HostedApi api = new HostedApi();
        if (!api.capabilities().schemaVersions.equals(java.util.List.of("1", "2", "3"))) throw new AssertionError("Capability contract");
        Session owner = login(api, root, "android-owner@example.test");
        Session other = login(api, root, "android-other@example.test");
        if (!api.me(owner).accountId.equals(owner.account.accountId)) throw new AssertionError("Owner binding");
        File storeRoot = new File(root, "client-store");
        HostedStore store = new HostedStore(storeRoot);
        byte[] bytes = Files.readAllBytes(new File(fixture, "scene.json").toPath());
        ViewerScene scene = ViewerPackageCodec.parseScene(bytes);
        HostedStore.Stage stage = store.begin(owner.account.accountId, scene.releaseId, ViewerPackageCodec.contentDigest(scene));
        Files.write(new File(stage.payload, "scene.json").toPath(), bytes);
        Files.createDirectory(new File(stage.payload, "assets").toPath());
        for (ViewerScene.Asset asset : scene.assets)
            Files.copy(new File(fixture, asset.path).toPath(), new File(stage.payload, asset.path).toPath());
        Task task = store.install(stage, UUID.randomUUID().toString(), 7, scene.title,
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ViewerPackageCodec.manifestBytes(scene))), 7);
        String createKey = task.createKey, commitKey = task.commitKey;
        String taskId = task.taskId;
        // Delete the test's original payload; the installed immutable snapshot must be sufficient.
        try (var paths = Files.walk(fixture.toPath())) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        LossyApi lost = new LossyApi();
        for (int loss = 0; loss < 3; loss++) {
            try { new HostedEngine(store, lost, session -> {}).step(owner, taskId); throw new AssertionError("Expected injected response loss"); }
            catch (IOException expected) {
                if (!"SYNTHETIC_RESPONSE_LOST".equals(expected.getMessage())) throw expected;
            }
            store = new HostedStore(storeRoot); // Actual journal re-open, not a test's shadow state.
            task = store.get(owner.account.accountId, taskId);
            if (!task.createKey.equals(createKey) || !task.commitKey.equals(commitKey)) throw new AssertionError("Unstable keys");
        }
        if (!lost.createLost || !lost.assetLost || !lost.commitLost) throw new AssertionError("Missing failure boundary");
        try { new HostedEngine(store, api, session -> {}).step(other, taskId); throw new AssertionError("Cross-account recovery"); }
        catch (IllegalArgumentException expected) {
            if (!"ACCOUNT_MISMATCH".equals(expected.getMessage())) throw expected;
        }
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (task.publication == null && System.nanoTime() < deadline) {
            task = new HostedEngine(store, api, session -> {}).step(owner, taskId);
            if (!"receiving".equals(task.state) && !"validating".equals(task.state) && !"committed".equals(task.state))
                throw new AssertionError("Unexpected final state: " + task.state + ", " + task.errorCode);
            if (task.publication == null) Thread.sleep(200);
        }
        if (task.publication == null) throw new AssertionError("No formal publication receipt");
        Publication publication = task.publication;
        if (!publication.releaseId.equals(scene.releaseId) || !publication.contentDigest.equals(ViewerPackageCodec.contentDigest(scene)))
            throw new AssertionError("Publication binding");
        Task replay = new HostedEngine(new HostedStore(storeRoot), api, session -> {}).step(owner, taskId);
        if (!replay.publication.publicationId.equals(publication.publicationId)) throw new AssertionError("Duplicate publication");
        store.requestCancellation(owner.account.accountId, taskId);
        Task cancelledAfterCommit = new HostedEngine(store, api, session -> {}).step(owner, taskId);
        if (!cancelledAfterCommit.cancelRequested || cancelledAfterCommit.publication == null || !"committed".equals(cancelledAfterCommit.state))
            throw new AssertionError("Cancellation hid committed publication");
        Page<Publication> versions = api.publications(owner, publication.serverProjectId, null);
        if (versions.items.size() != 1 || versions.nextCursor != null) throw new AssertionError("Publication replay created another version");
        // Twenty-one extra synthetic project identities exercise actual cursor pagination.
        for (int i = 0; i < 21; i++) api.createProject(owner, UUID.randomUUID().toString(), "Synthetic page " + i);
        Set<String> projects = new HashSet<>(); String cursor = null; int pages = 0;
        do {
            Page<Project> page = api.projects(owner, cursor); pages++;
            for (Project project : page.items) if (!projects.add(project.serverProjectId)) throw new AssertionError("Duplicate page item");
            cursor = page.nextCursor;
        } while (cursor != null && pages < 4);
        if (pages != 2 || projects.size() != 22 || cursor != null) throw new AssertionError("Cursor pagination");
        Revocation revoked = api.revoke(owner, publication.publicationId);
        store.recordRevocation(owner.account.accountId, revoked);
        if (!"revoked".equals(api.publication(owner, publication.publicationId).status)) throw new AssertionError("Unconfirmed revocation");
        api.revokeSession(owner);
        try { api.me(owner); throw new AssertionError("Session still active"); }
        catch (HostedApi.ApiException expected) { if (expected.statusCode != 401) throw expected; }
        api.revokeSession(other);
        System.out.println("HOST_HOSTED_HTTP production Java engine/Fastify/PostgreSQL synthetic login/create-PUT-commit-response-loss/reopen/owner-isolation/pagination/cancel-after-commit/revoke/logout PASS");
        System.out.println("HOST_HOSTED_HTTP_LIMIT Android OS networking/Keystore/adb reverse/process death/video decode NOT_RUN");
    }
    private static Session login(HostedApi api, File root, String email) throws Exception {
        Challenge challenge = api.requestChallenge(email);
        // Harness-only exact challenge file. Android production has no server filesystem/inbox code.
        File inbox = new File(root, "server/inbox/" + challenge.challengeId + ".json");
        Map<String,Object> m = HostedApi.object(HostedJson.parse(Files.readAllBytes(inbox.toPath()), 8192));
        if (!email.equals(m.get("email")) || !challenge.challengeId.equals(m.get("challengeId"))) throw new AssertionError("Synthetic inbox binding");
        return api.createSession(challenge.challengeId, HostedApi.string(m, "code"));
    }
    private static final class LossyApi extends HostedApi {
        boolean createLost, assetLost, commitLost;
        @Override public Upload createUpload(Session session, Task task) throws IOException {
            Upload upload = super.createUpload(session, task);
            if (!createLost) { createLost = true; throw new IOException("SYNTHETIC_RESPONSE_LOST"); }
            return upload;
        }
        @Override public void putAsset(Session session, String uploadId, Asset asset, File file) throws IOException {
            super.putAsset(session, uploadId, asset, file);
            if (!assetLost) { assetLost = true; throw new IOException("SYNTHETIC_RESPONSE_LOST"); }
        }
        @Override public Commit commit(Session session, Task task) throws IOException {
            Commit commit = super.commit(session, task);
            if (!commitLost) { commitLost = true; throw new IOException("SYNTHETIC_RESPONSE_LOST"); }
            return commit;
        }
    }
}
