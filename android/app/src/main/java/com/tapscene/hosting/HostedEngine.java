package com.tapscene.hosting;

import static com.tapscene.hosting.HostedApi.*;
import static com.tapscene.hosting.HostedModels.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** One resumable network pass. No ProjectStore/ReleaseStore lock is ever held by this engine. */
public final class HostedEngine {
    public interface SessionGuard { void check(Session session) throws IOException; }
    private static final ConcurrentHashMap<String,ReentrantLock> RUNNERS = new ConcurrentHashMap<>();
    private final HostedStore store;
    private final HostedApi api;
    private final SessionGuard guard;
    public HostedEngine(HostedStore store, HostedApi api, SessionGuard guard) {
        this.store=Objects.requireNonNull(store);this.api=Objects.requireNonNull(api);this.guard=Objects.requireNonNull(guard);
    }
    /** Call again after a delay while state is validating/receipt_pending. Unknown/401 retains the same task. */
    public Task step(Session session,String taskId) throws IOException {
        String accountId=session.account.accountId;
        ReentrantLock runner=RUNNERS.computeIfAbsent(accountId+":"+id(taskId),x->new ReentrantLock());
        try { runner.lockInterruptibly(); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new InterruptedIOException("TRANSFER_INTERRUPTED"); }
        try {
            check(session);Task task=store.get(accountId,taskId);
            if(task.publication!=null)return task;
            if(task.cancelRequested && task.createRequest==null)return store.recordState(accountId,taskId,"cancelled",null);
            if(!task.cancelRequested && Arrays.asList("cancelled","expired","failed").contains(task.state))return task;
            boolean queryUnknown = !task.cancelRequested && terminal(task.state);
            if(queryUnknown) {
                // An explicit retry may reconcile a future server state, but it must remain read-only.
                if(task.uploadId==null)return task;
                check(session);Upload observed=api.uploadStatus(session,task.uploadId);
                require(task.releaseId.equals(observed.releaseId),"RELEASE_MISMATCH");
                return store.recordUpload(accountId,taskId,observed);
            }
            if(task.serverProjectId==null) {
                check(session);Project project=api.createProject(session,task.localProjectId,task.title);
                task=store.bindProject(accountId,taskId,project.serverProjectId);
            }
            if(task.uploadId==null) {
                check(session);Upload created=api.createUpload(session,task);
                task=store.recordUpload(accountId,taskId,created);
            }
            task=store.get(accountId,taskId);
            if(task.cancelRequested)return cancel(session,task);
            check(session);Upload remote=api.uploadStatus(session,task.uploadId);
            require(task.releaseId.equals(remote.releaseId),"RELEASE_MISMATCH");
            task=store.recordUpload(accountId,taskId,remote);
            if(store.get(accountId,taskId).cancelRequested)return cancel(session,store.get(accountId,taskId));
            if(task.publication!=null || !"receiving".equals(task.state))return task;
            List<Asset> assets=store.assets(task);Set<String> declared=new HashSet<>();for(Asset a:assets)declared.add(a.id);
            require(declared.containsAll(remote.missingAssetIds),"UNDECLARED_SERVER_ASSET");
            for(Asset asset:assets) {
                check(session);task=store.get(accountId,taskId);if(task.cancelRequested)return cancel(session,task);
                if(remote.missingAssetIds.contains(asset.id))api.putAsset(session,task.uploadId,asset,store.assetFile(accountId,taskId,asset));
            }
            check(session);task=store.get(accountId,taskId);if(task.cancelRequested)return cancel(session,task);
            Commit result=api.commit(session,task);
            if(result.publication!=null)task=store.recordPublication(accountId,taskId,result.publication);
            else task=store.recordState(accountId,taskId,"validating",null);
            // A cancellation that raced commit persists its result. A committed publication needs a separate revoke confirmation.
            if(store.get(accountId,taskId).cancelRequested && task.publication==null)return cancel(session,store.get(accountId,taskId));
            return store.get(accountId,taskId);
        } catch(ApiException e) {
            Task latest=store.get(accountId,taskId);store.recordState(accountId,taskId,latest.state,e.code);throw e;
        } catch(IOException e) {
            Task latest=store.get(accountId,taskId);store.recordState(accountId,taskId,latest.state,"CONNECTION_INTERRUPTED");throw e;
        } catch(IllegalArgumentException e) {
            Task latest=store.get(accountId,taskId);store.recordState(accountId,taskId,latest.state,safeCode(e.getMessage()));throw e;
        } finally { runner.unlock(); }
    }
    private Task cancel(Session session,Task task) throws IOException {
        if(task.publication!=null)return task;
        check(session);Upload result=api.cancelUpload(session,task.uploadId);
        Task updated=store.recordUpload(session.account.accountId,task.taskId,result);
        if("receipt_pending".equals(updated.state)) {
            check(session);Upload status=api.uploadStatus(session,task.uploadId);
            require(task.releaseId.equals(status.releaseId),"RELEASE_MISMATCH");updated=store.recordUpload(session.account.accountId,task.taskId,status);
        }
        return updated;
    }
    private void check(Session session) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("TRANSFER_INTERRUPTED");guard.check(session);
    }
    public static boolean terminal(String state) { return !Arrays.asList("prepared","receiving","validating","receipt_pending").contains(state); }
}
