package com.tapscene.hosting;

import java.io.IOException;

/** Tokens may only be persisted through this boundary. Host tests inject an in-memory fake. */
public interface SessionVault {
    HostedModels.Session load() throws IOException;
    void save(HostedModels.Session session) throws IOException;
    void clearIfToken(String token) throws IOException;
    void queueRevocation(HostedModels.Session session) throws IOException;
    java.util.List<HostedModels.Session> pendingRevocations() throws IOException;
    void removePendingToken(String token) throws IOException;
}
