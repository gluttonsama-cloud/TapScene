package com.tapscene.hosting;

import static com.tapscene.hosting.HostedApi.*;
import static com.tapscene.hosting.HostedModels.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic, host-only checks. These do not claim Android Keystore/device verification. */
public final class HostedCoreChecks {
    private static final String ACCOUNT="10000000-0000-4000-8000-000000000001", OTHER="10000000-0000-4000-8000-000000000002";
    private static final String PROJECT="20000000-0000-4000-8000-000000000001", SERVER="20000000-0000-4000-8000-000000000002";
    private static final String RELEASE="30000000-0000-4000-8000-000000000001", ASSET="40000000-0000-4000-8000-000000000001";
    private static final String UPLOAD="50000000-0000-4000-8000-000000000001", PUBLICATION="60000000-0000-4000-8000-000000000001";
    private static final String DATE="2026-10-10T10:00:00.000Z", EXPIRES="2026-10-17T10:00:00.000Z";
    private static final Session SESSION=new Session(new Account(ACCOUNT,"synthetic@example.test"),EXPIRES,"a".repeat(43));
    private static int assertions;
    public static void main(String[] args) throws Exception {
        urlsAndWire(); journal(); recovery(); cancellationRaces(); sessionRecovery();
        System.out.println("PASS HostedCoreChecks: " + assertions + " assertions (synthetic; Keystore/device NOT_RUN)");
    }
    private static void urlsAndWire() throws Exception {
        equal(ENDPOINT+"/api/v1/publication-uploads/"+UPLOAD,validateStatusUrl("/api/v1/publication-uploads/"+UPLOAD,UPLOAD));
        equal(ENDPOINT+"/s/"+"x".repeat(43),validateShareUrl(ENDPOINT+"/s/"+"x".repeat(43)));
        for(String bad:List.of("https://127.0.0.1:4173/s/"+"x".repeat(43),"http://localhost:4173/s/"+"x".repeat(43),
                "http://127.0.0.1:4174/s/"+"x".repeat(43),"http://user@127.0.0.1:4173/s/"+"x".repeat(43),
                "//127.0.0.1:4173/s/"+"x".repeat(43),ENDPOINT+"/s/"+"x".repeat(43)+"#fragment",
                ENDPOINT+"/s/"+"x".repeat(43)+"?token=x",ENDPOINT+"/s/../s/"+"x".repeat(43),"file:///tmp/secret")) {
            rejects(()->validateShareUrl(bad));
        }
        rejects(()->validateStatusUrl(ENDPOINT+"/api/v1/publication-uploads/"+RELEASE,UPLOAD));
        rejects(()->syntheticEmail("real@gmail.com"));syntheticEmail("test+safe@example.test");
        FakeConnection redirect=new FakeConnection(302,"{}");HostedApi api=new HostedApi(url->{equal("127.0.0.1",url.getHost());return redirect;});
        try{api.me(SESSION);fail("redirect");}catch(HostedApi.ApiException e){equal("REDIRECT_REFUSED",e.code);}
        check(!redirect.getInstanceFollowRedirects());equal("Bearer "+SESSION.bearerToken(),redirect.getRequestProperty("Authorization"));check(redirect.disconnected);
        FakeConnection error=new FakeConnection(409,"{\"error\":{\"code\":\"PUBLICATION_LIMIT\"},\"message\":\"secret-url\"}");
        try{new HostedApi(url->error).me(SESSION);fail("error");}catch(HostedApi.ApiException e){equal("PUBLICATION_LIMIT",e.code);check(!e.toString().contains("secret"));}
        FakeConnection wrongError=new FakeConnection(401,"{\"code\":\"LEAKED_WRONG_FIELD\",\"error\":{\"message\":\"secret\"}}");
        try{new HostedApi(url->wrongError).me(SESSION);fail("error");}catch(HostedApi.ApiException e){equal("HTTP_ERROR",e.code);}
        String caps="{\"schemaVersions\":[\"1\",\"2\",\"3\"],\"policyVersions\":[\"static-viewer-1\"],\"expiryDays\":[1,7,30],\"defaultExpiryDays\":7,\"limits\":{\"packageBytes\":52428800},\"mode\":\"local\",\"authentication\":\"synthetic-mailbox-only\"}";
        equal(List.of("1","2","3"),new HostedApi(url->new FakeConnection(200,caps)).capabilities().schemaVersions);
        rejects(()->new HostedApi(url->new FakeConnection(200,caps.replace("[\"1\",\"2\",\"3\"]","[1,2,3]"))).capabilities());
        rejects(()->HostedJson.parse("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8),100));
    }
    private static void journal() throws Exception {
        Fixture f=fixture();Task t=f.task;HostedStore reopened=new HostedStore(f.root);
        equal(t.createKey,reopened.get(ACCOUNT,t.taskId).createKey);equal(0,reopened.tasks(OTHER).size());
        rejects(()->reopened.get(OTHER,f.task.taskId));
        t=reopened.bindProject(ACCOUNT,t.taskId,SERVER);String taskId=t.taskId;
        check(t.createRequest.contains("\"expiryDays\":7"));equal(t.createRequest,new HostedStore(f.root).get(ACCOUNT,taskId).createRequest);
        rejects(()->reopened.bindProject(ACCOUNT,taskId,PROJECT));
        reopened.requestCancellation(ACCOUNT,taskId);reopened.recordState(ACCOUNT,taskId,"receiving",null);
        check(new HostedStore(f.root).get(ACCOUNT,taskId).cancelRequested);
        Publication wrong=new Publication(PUBLICATION,SERVER,RELEASE,1,"0".repeat(64),"Synthetic",DATE,EXPIRES,"active",ENDPOINT+"/s/"+"x".repeat(43),null);
        rejects(()->reopened.recordPublication(ACCOUNT,taskId,wrong));
        for(Path p:Files.walk(f.root.toPath()).filter(Files::isRegularFile).toList())check(!Files.readString(p).contains(SESSION.bearerToken()));
        Asset asset=reopened.assets(t).get(0);File bound=reopened.assetFile(ACCOUNT,taskId,asset);check(bound.isFile());
        Files.write(bound.toPath(),new byte[]{4});rejects(()->reopened.assetFile(ACCOUNT,taskId,asset));
        Fixture pending=fixture();pending.store.requestRevocation(ACCOUNT,PUBLICATION);
        check(new HostedStore(pending.root).pendingRevocations(ACCOUNT).contains(PUBLICATION));check(pending.store.pendingRevocations(OTHER).isEmpty());
        pending.store.recordRevocation(ACCOUNT,new Revocation(PUBLICATION,DATE,DATE));check(pending.store.pendingRevocations(ACCOUNT).isEmpty());
        pending.store.cachePublications(ACCOUNT,List.of(publication(pending.task)));
        equal("revoked",pending.store.cachedPublications(ACCOUNT,SERVER).get(0).status);
        // An unowned staging sibling is not deleted by recovery.
        File unknown=new File(pending.root,"staging/"+UUID.randomUUID());check(unknown.mkdir());new HostedStore(pending.root);check(unknown.exists());
        // Exact owner-scoped recovery does not touch a neighboring unknown directory.
        String abandoned=UUID.randomUUID().toString();File owned=new File(pending.root,"staging/"+abandoned);check(owned.mkdir());
        Files.write(new File(owned,"owner.json").toPath(),json(map("owner","tapscene-hosted-stage-v1","taskId",abandoned,"endpoint",ENDPOINT,"accountId",ACCOUNT,"releaseId",RELEASE,"contentDigest",pending.task.contentDigest)));
        new HostedStore(pending.root);check(!owned.exists());check(unknown.exists());
        // Simulate process death after formal revocation ack, before updating cached/task projections.
        Fixture ack=fixture();ack.store.bindProject(ACCOUNT,ack.task.taskId,SERVER);ack.store.recordPublication(ACCOUNT,ack.task.taskId,publication(ack.task));
        ack.store.cachePublications(ACCOUNT,List.of(publication(ack.task)));ack.store.requestRevocation(ACCOUNT,PUBLICATION);
        File ackFile=new File(ack.root,"receipts/"+ACCOUNT+"/"+PUBLICATION+".revoke.json");
        Files.write(ackFile.toPath(),json(map("publicationId",PUBLICATION,"status","revoked","revokedAt",DATE,"serverConfirmedAt",DATE)));
        HostedStore afterAck=new HostedStore(ack.root);equal("revoked",afterAck.get(ACCOUNT,ack.task.taskId).publication.status);
        equal("revoked",afterAck.cachedPublications(ACCOUNT,SERVER).get(0).status);check(afterAck.pendingRevocations(ACCOUNT).isEmpty());
    }
    private static void recovery() throws Exception {
        Fixture f=fixture();FakeApi api=new FakeApi(f.task);api.loseCreate=true;
        HostedEngine engine=new HostedEngine(f.store,api,s->{});
        HostedEngine firstEngine=engine;failsIo(()->firstEngine.step(SESSION,f.task.taskId));equal(1,api.createCalls);check(f.store.get(ACCOUNT,f.task.taskId).uploadId==null);
        String key=f.store.get(ACCOUNT,f.task.taskId).createKey;
        HostedStore reopened=new HostedStore(f.root);engine=new HostedEngine(reopened,api,s->{});Task result=engine.step(SESSION,f.task.taskId);
        equal("validating",result.state);check(result.publication==null);equal(2,api.createCalls);equal(1,api.uploadsCreated);equal(key,api.lastCreateKey);equal(1,api.commitCalls);
        result=engine.step(SESSION,f.task.taskId);equal("validating",result.state);equal(1,api.commitCalls);
        api.state="committed";result=engine.step(SESSION,f.task.taskId);equal("committed",result.state);check(result.publication!=null);
        reopened.requestCancellation(ACCOUNT,f.task.taskId);result=engine.step(SESSION,f.task.taskId);check(result.cancelRequested);check(result.publication!=null);equal(0,api.cancelCalls);
        Fixture c=fixture();FakeApi cApi=new FakeApi(c.task);cApi.loseCreate=true;HostedEngine cEngine=new HostedEngine(c.store,cApi,s->{});
        failsIo(()->cEngine.step(SESSION,c.task.taskId));c.store.requestCancellation(ACCOUNT,c.task.taskId);
        result=new HostedEngine(new HostedStore(c.root),cApi,s->{}).step(SESSION,c.task.taskId);equal("cancelled",result.state);equal(1,cApi.uploadsCreated);equal(1,cApi.cancelCalls);equal(0,cApi.commitCalls);
        Fixture unknown=fixture();FakeApi unknownApi=new FakeApi(unknown.task);unknownApi.state="future_server_state";
        HostedEngine unknownEngine=new HostedEngine(unknown.store,unknownApi,s->{});result=unknownEngine.step(SESSION,unknown.task.taskId);
        equal("future_server_state",result.state);equal(0,unknownApi.commitCalls);unknownEngine.step(SESSION,unknown.task.taskId);equal(1,unknownApi.createCalls);
        int priorQueries=unknownApi.statusCalls;unknownApi.state="receiving";result=unknownEngine.step(SESSION,unknown.task.taskId);
        equal("receiving",result.state);equal(priorQueries+1,unknownApi.statusCalls);equal(0,unknownApi.putCalls);equal(0,unknownApi.commitCalls);equal(1,unknownApi.createCalls);
        unknown.store.recordState(ACCOUNT,unknown.task.taskId,"future_server_state",null);unknownApi.state="committed";
        result=unknownEngine.step(SESSION,unknown.task.taskId);check(result.publication!=null);equal("committed",result.state);equal(1,unknownApi.createCalls);equal(0,unknownApi.commitCalls);
        Fixture auth=fixture();HostedEngine denied=new HostedEngine(auth.store,new FakeApi(auth.task),s->{throw new HostedApi.ApiException(401,"AUTH_REQUIRED",0);});
        failsIo(()->denied.step(SESSION,auth.task.taskId));result=auth.store.get(ACCOUNT,auth.task.taskId);equal("prepared",result.state);equal("AUTH_REQUIRED",result.errorCode);
    }
    private static void cancellationRaces() throws Exception {
        Fixture f=fixture();FakeApi api=new FakeApi(f.task);api.assetStarted=new CountDownLatch(1);api.assetContinue=new CountDownLatch(1);
        AtomicReference<Task> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{try{result.set(new HostedEngine(f.store,api,s->{}).step(SESSION,f.task.taskId));}catch(Throwable e){error.set(e);}});
        worker.start();check(api.assetStarted.await(5,TimeUnit.SECONDS));f.store.requestCancellation(ACCOUNT,f.task.taskId);api.assetContinue.countDown();worker.join(5000);
        check(!worker.isAlive());if(error.get()!=null)throw new AssertionError(error.get());equal("cancelled",result.get().state);equal(0,api.commitCalls);
        Fixture race=fixture();FakeApi raceApi=new FakeApi(race.task);raceApi.commitStarted=new CountDownLatch(1);raceApi.commitContinue=new CountDownLatch(1);
        Thread commitWorker=new Thread(()->{try{result.set(new HostedEngine(race.store,raceApi,s->{}).step(SESSION,race.task.taskId));}catch(Throwable e){error.set(e);}});
        commitWorker.start();check(raceApi.commitStarted.await(5,TimeUnit.SECONDS));race.store.requestCancellation(ACCOUNT,race.task.taskId);raceApi.commitContinue.countDown();commitWorker.join(5000);
        check(!commitWorker.isAlive());if(error.get()!=null)throw new AssertionError(error.get());check(result.get().cancelRequested);check(result.get().publication!=null);equal("committed",result.get().state);
    }
    private static void sessionRecovery() throws Exception {
        MemoryVault vault=new MemoryVault();FakeApi api=new FakeApi(null);HostedSessions sessions=new HostedSessions(api,vault);
        Session created=sessions.create(UPLOAD,"123456");equal(1,sessions.pendingCount());check(vault.load()==null);
        api.failRevoke=true;Session late=created;failsIo(()->sessions.revoke(late));equal(1,new HostedSessions(api,vault).pendingCount());check(vault.load()==null);
        Session current=new Session(new Account(OTHER,"other@example.test"),EXPIRES,"b".repeat(43));vault.save(current);
        api.failRevoke=false;sessions.retryPending();equal(0,sessions.pendingCount());check(vault.load()==current);
        created=sessions.create(UPLOAD,"123456");vault.failSave=true;Session install=created;failsIo(()->sessions.install(install));check(sessions.pendingCount()>=1);
        vault.failSave=false;sessions.retryPending();equal(0,sessions.pendingCount());check(vault.load()==null);
        Session logged=sessions.create(UPLOAD,"123456");sessions.install(logged);equal(0,sessions.pendingCount());check(vault.load()==logged);
        api.failRevoke=true;failsIo(()->sessions.revoke(logged));check(vault.load()==logged);equal(1,sessions.pendingCount());
        api.failRevoke=false;api.alreadyRevoked=true;sessions.retryPending();check(vault.load()==null);equal(0,sessions.pendingCount());
        check(!logged.toString().contains(logged.bearerToken()));
        api.alreadyRevoked=false;Session cancelled=sessions.create(UPLOAD,"123456");sessions.install(cancelled);api.failRevoke=true;
        failsIo(()->sessions.retire(cancelled));check(vault.load()==null);equal(1,new HostedSessions(api,vault).pendingCount());
        vault.save(current);api.failRevoke=false;sessions.retryPending();check(vault.load()==current);equal(0,sessions.pendingCount());
        vault.failQueue=true;vault.active=SESSION;api.failRevoke=true;
        try { sessions.retire(SESSION);fail("cleanup must report missing durable credential"); }
        catch(HostedSessions.CleanupException e) { check(!e.credentialPreserved);check(!e.serverConfirmed);check(e.activeCleared); }
        check(vault.load()==null);api.failRevoke=false;sessions.revoke(SESSION);check(vault.load()==null);
        Session diskFailed=new Session(new Account(ACCOUNT,"synthetic@example.test"),EXPIRES,"z".repeat(43));
        vault.active=diskFailed;vault.failClear=true;api.failRevoke=true;
        try { sessions.retire(diskFailed);fail("full disk must report cleanup failure"); }
        catch(HostedSessions.CleanupException e) { check(!e.credentialPreserved);check(!e.activeCleared);check(!e.serverConfirmed); }
        check(vault.load()==diskFailed);check(sessions.current()==null);check(new HostedSessions(api,vault).current()==null);
        vault.failQueue=false;vault.failClear=false;api.failRevoke=false;sessions.retire(diskFailed);check(vault.load()==null);
        vault.failQueue=true;
        try { sessions.create(UPLOAD,"123456");fail("failed encryption must not install a session"); }
        catch(HostedSessions.CleanupException e) { check(e.serverConfirmed);check(e.activeCleared);check(!e.credentialPreserved); }
        check(vault.load()==null);
    }
    private static Fixture fixture() throws Exception {
        File root=Files.createTempDirectory("hosted-core-").toFile();HostedStore store=new HostedStore(root);byte[] bytes="synthetic-only".getBytes(StandardCharsets.UTF_8);
        Map<String,Object> scene=map("schemaVersion",1,"releaseId",RELEASE,"title","Synthetic","assets",List.of(map("id",ASSET,"path","assets/"+ASSET+".png","mime","image/png","sha256",sha(bytes),"byteLength",bytes.length)));
        byte[] canonical=json(scene);HostedStore.Stage stage=store.begin(ACCOUNT,RELEASE,sha(canonical));
        check(new File(stage.payload,"assets").mkdir());Files.write(new File(stage.payload,"scene.json").toPath(),canonical);
        Files.write(new File(stage.payload,"assets/"+ASSET+".png").toPath(),bytes);
        Task task=store.install(stage,PROJECT,3,"Synthetic","d".repeat(64),7);return new Fixture(root,store,task);
    }
    private static Publication publication(Task t) { return new Publication(PUBLICATION,SERVER,RELEASE,1,t.contentDigest,"Synthetic",DATE,EXPIRES,"active",ENDPOINT+"/s/"+"x".repeat(43),null); }
    private static final class Fixture { final File root;final HostedStore store;final Task task;Fixture(File r,HostedStore s,Task t){root=r;store=s;task=t;} }
    private static final class FakeApi extends HostedApi {
        private static int sessionOrdinal;
        final Task initial;String state="receiving",lastCreateKey;int createCalls,uploadsCreated,commitCalls,cancelCalls,statusCalls,putCalls;boolean loseCreate,received,failRevoke,alreadyRevoked;
        CountDownLatch assetStarted,assetContinue,commitStarted,commitContinue;
        FakeApi(Task task){initial=task;}
        @Override public Project createProject(Session s,String local,String title){return new Project(SERVER,local,title,0,0);}
        @Override public Upload createUpload(Session s,Task task)throws IOException{createCalls++;if(lastCreateKey==null){uploadsCreated++;lastCreateKey=task.createKey;}else equal(lastCreateKey,task.createKey);if(loseCreate){loseCreate=false;throw new IOException("synthetic lost response");}return upload(false);}
        @Override public Upload uploadStatus(Session s,String id){statusCalls++;return upload(true);}
        @Override public void putAsset(Session s,String id,Asset asset,File file)throws IOException{putCalls++;if(assetStarted!=null){assetStarted.countDown();await(assetContinue);}received=true;}
        @Override public Commit commit(Session s,Task t)throws IOException{commitCalls++;if(commitStarted!=null){state="committed";commitStarted.countDown();await(commitContinue);}else state="validating";return new Commit(UPLOAD,"/api/v1/publication-uploads/"+UPLOAD,1,null);}
        @Override public Upload cancelUpload(Session s,String id){cancelCalls++;if(!state.equals("committed"))state="cancelled";return upload(true);}
        @Override public Session createSession(String challengeId,String code){return new Session(SESSION.account,EXPIRES,String.format(java.util.Locale.ROOT,"%043d",++sessionOrdinal));}
        @Override public void revokeSession(Session s)throws IOException{if(failRevoke)throw new IOException("synthetic offline");if(alreadyRevoked)throw new ApiException(401,"AUTH_REQUIRED",0);}
        private Upload upload(boolean detail){return new Upload(UPLOAD,detail?RELEASE:null,state,DATE,received?new ArrayList<>():new ArrayList<>(List.of(ASSET)),null,detail&&state.equals("committed")?HostedCoreChecks.publication(initial):null);}
        private static void await(CountDownLatch latch)throws IOException{try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
    }
    private static final class MemoryVault implements SessionVault {
        Session active;boolean failSave,failQueue,failClear;final Map<String,Session> pending=new HashMap<>();
        @Override public Session load(){return active;}
        @Override public void save(Session s)throws IOException{if(failSave)throw new IOException("synthetic vault write failure");active=s;}
        @Override public void clearIfToken(String token)throws IOException{if(failClear)throw new IOException("synthetic disk unavailable");if(active!=null&&active.bearerToken().equals(token))active=null;}
        @Override public void queueRevocation(Session s)throws IOException{if(failQueue)throw new IOException("synthetic disk full");pending.put(s.bearerToken(),s);}
        @Override public List<Session> pendingRevocations(){return new ArrayList<>(pending.values());}
        @Override public void removePendingToken(String token){pending.remove(token);}
    }
    private static final class FakeConnection extends HttpURLConnection {
        final int status;final byte[] body;boolean disconnected;
        FakeConnection(int status,String body)throws MalformedURLException{super(URI.create(ENDPOINT).toURL());this.status=status;this.body=body.getBytes(StandardCharsets.UTF_8);}
        @Override public void connect(){}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public int getResponseCode(){return status;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(body);}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
    }
    private interface Action{void run()throws Exception;}
    private static void rejects(Action a)throws Exception{try{a.run();fail("expected rejection");}catch(IllegalArgumentException expected){assertions++;}}
    private static void failsIo(Action a)throws Exception{try{a.run();fail("expected IO failure");}catch(IOException expected){assertions++;}}
    private static void equal(Object expected,Object actual){assertions++;if(!Objects.equals(expected,actual))throw new AssertionError("expected "+expected+", actual "+actual);}
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("check failed");}
    private static void fail(String message){throw new AssertionError(message);}
}
