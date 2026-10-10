package com.tapscene.hosting;

import static com.tapscene.hosting.HostedModels.*;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Session ownership transactions, shared by the Android repository and host failure tests. */
public final class HostedSessions {
    // A process-local fail-closed guard when the filesystem itself cannot persist or clear retirement.
    private static final java.util.Set<String> RETIRED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static String fingerprint(Session session) { return HostedApi.sha(session.bearerToken().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
    public static final class CleanupException extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean credentialPreserved, serverConfirmed, activeCleared;
        CleanupException(boolean preserved, boolean confirmed, boolean cleared, Throwable cause) {
            super(confirmed ? "SESSION_LOCAL_CLEANUP_FAILED" : preserved ? "SESSION_CLEANUP_PENDING" : "SESSION_CLEANUP_UNCONFIRMED", cause);
            credentialPreserved=preserved;serverConfirmed=confirmed;activeCleared=cleared;
        }
    }
    private final HostedApi api;
    private final SessionVault vault;
    public HostedSessions(HostedApi api, SessionVault vault) { this.api=Objects.requireNonNull(api);this.vault=Objects.requireNonNull(vault); }
    public Session create(String challengeId, String code) throws IOException {
        Session session=api.createSession(challengeId,code);
        try { vault.queueRevocation(session); }
        catch(IOException | RuntimeException error) {
            // Never install a credential when encryption/storage failed. Cleanup still attempts the server.
            retire(session);
            // Keep the known server cleanup result even though the caller never received a Session.
            throw new CleanupException(false,true,true,error);
        }
        return session;
    }
    public Session current() throws IOException {
        Session session=vault.load();return session==null || RETIRED.contains(fingerprint(session)) ? null : session;
    }
    public void install(Session session) throws IOException {
        if(RETIRED.contains(fingerprint(session)))throw new IOException("SESSION_RETIRED");
        vault.queueRevocation(session);
        Session previous=vault.load();
        if(previous!=null && !previous.bearerToken().equals(session.bearerToken()))vault.queueRevocation(previous);
        // Queue first. A crash between the writes leaves a credential available for explicit cleanup.
        vault.save(session);vault.removePendingToken(session.bearerToken());
    }
    /** Normal logout retains the active credential until the server confirms it is invalid. */
    public void revoke(Session session) throws IOException { cleanup(session,false); }
    /** A dismissed login is first moved out of active state, while its cleanup credential is retained. */
    public void retire(Session session) throws IOException { RETIRED.add(fingerprint(session));cleanup(session,true); }
    private void cleanup(Session session, boolean retire) throws IOException {
        boolean preserved=false,cleared=false;
        try { vault.queueRevocation(session);preserved=true; }
        catch(IOException | RuntimeException ignored) { /* Best-effort server cleanup still follows. */ }
        if(retire) {
            try { vault.clearIfToken(session.bearerToken());cleared=true; }
            catch(IOException | RuntimeException ignored) { /* Best-effort server cleanup still follows. */ }
        }
        // A full/unavailable disk must not suppress the best-effort server DELETE.
        try { api.revokeSession(session); }
        catch(HostedApi.ApiException error) {
            if(error.statusCode!=401)throw new CleanupException(preserved,false,cleared,error);
        } catch(IOException | RuntimeException error) { throw new CleanupException(preserved,false,cleared,error); }
        RETIRED.add(fingerprint(session));
        try { vault.clearIfToken(session.bearerToken());cleared=true;vault.removePendingToken(session.bearerToken()); }
        catch(IOException | RuntimeException error) { throw new CleanupException(preserved,true,cleared,error); }
        // Any earlier queue failure is now harmless: the server invalidated the credential and local cleanup completed.
    }
    public int pendingCount() throws IOException { return vault.pendingRevocations().size(); }
    public void retryPending() throws IOException {
        List<Session> pending=vault.pendingRevocations();
        for(Session session:pending)revoke(session);
    }
}
