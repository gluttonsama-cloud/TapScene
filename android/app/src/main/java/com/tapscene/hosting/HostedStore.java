package com.tapscene.hosting;

import static com.tapscene.hosting.HostedApi.*;
import static com.tapscene.hosting.HostedModels.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Independent no-backup journal. Each task owns its exact immutable payload, even after local deletion. */
public final class HostedStore {
    private static final int MAX_TASK_BYTES = 2 * 1024 * 1024;
    private static final ConcurrentHashMap<String,Object> LOCKS = new ConcurrentHashMap<>();
    private static final Set<String> ACTIVE_STAGES = ConcurrentHashMap.newKeySet();
    private final File root, staging, tasks, receipts;
    private final Object lock;
    public interface DirectorySync { void sync(File directory) throws IOException; }
    private final DirectorySync sync;
    public HostedStore(File root) throws IOException { this(root, directory -> {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { channel.force(true); }
    }); }
    public HostedStore(File root, DirectorySync sync) throws IOException {
        this.root = root.getAbsoluteFile(); this.sync = sync;
        require(root.getCanonicalFile().equals(this.root), "UNSAFE_STORE_PATH");
        lock = LOCKS.computeIfAbsent(this.root.toString(), x -> new Object());
        synchronized (lock) {
            mkdir(this.root); staging = new File(this.root,"staging"); tasks = new File(this.root,"tasks"); receipts = new File(this.root,"receipts");
            mkdir(staging); mkdir(tasks); mkdir(receipts); sync.sync(this.root);
            recoverStaging();
        }
    }
    public static final class Stage {
        public final String taskId, accountId, releaseId, contentDigest;
        public final File payload;
        private final File directory;
        private Stage(String task, String account, String release, String digest, File directory) {
            taskId=task; accountId=account; releaseId=release; contentDigest=digest; this.directory=directory; payload=new File(directory,"payload");
        }
    }
    public Stage begin(String accountId, String releaseId, String digest) throws IOException {
        synchronized (lock) {
            id(accountId); id(releaseId); digest(digest); String taskId = UUID.randomUUID().toString();
            File dir = child(staging,taskId); require(!dir.exists(),"TASK_COLLISION"); mkdir(dir);
            Stage stage = new Stage(taskId,accountId,releaseId,digest,dir);
            writeAtomic(new File(dir,"owner.json"), json(owner(stage)));
            sync.sync(dir); sync.sync(staging); ACTIVE_STAGES.add(dir.toString()); mkdir(stage.payload); sync.sync(dir);
            return stage;
        }
    }
    public Task install(Stage stage, String localProjectId, long revision, String title,
            String fileListDigest, int expiryDays) throws IOException {
        synchronized (lock) {
            verifyOwner(stage); id(localProjectId); digest(fileListDigest); require(Arrays.asList(1,7,30).contains(expiryDays),"INVALID_EXPIRY");
            byte[] scene = read(new File(stage.payload,"scene.json"), 600*1024);
            Map<String,Object> sceneMap = object(HostedJson.parse(scene,600*1024));
            require(Arrays.equals(scene,json(sceneMap)) && sha(scene).equals(stage.contentDigest) &&
                stage.releaseId.equals(string(sceneMap,"releaseId")) && title.equals(string(sceneMap,"title")),"SNAPSHOT_SCENE_MISMATCH");
            List<Asset> assets = assets(sceneMap); long bytes = scene.length;
            for (Asset asset : assets) bytes = Math.addExact(bytes,asset.byteLength);
            require(bytes <= 50L*1024*1024,"PACKAGE_BYTES_EXCEEDED");
            Task task = new Task(stage.taskId,ENDPOINT,stage.accountId,localProjectId,stage.releaseId,stage.contentDigest,title,
                revision,bytes,expiryDays,assets.size(),new String(scene,StandardCharsets.UTF_8),fileListDigest,
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,
                new String(json(map("releaseId",stage.releaseId,"contentDigest",stage.contentDigest)),StandardCharsets.UTF_8),
                null,null,"prepared",false,null,null);
            verifyPayload(stage.payload,task); syncTree(stage.payload); writeAtomic(new File(stage.directory,"task.json"), json(taskMap(task)));
            sync.sync(stage.directory); File destination=child(tasks,stage.taskId); require(!destination.exists(),"TASK_COLLISION");
            Files.move(stage.directory.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
            ACTIVE_STAGES.remove(stage.directory.toString());
            // Installation is committed; later directory fsync failure never deletes the installed snapshot.
            sync.sync(tasks); sync.sync(staging); return task;
        }
    }
    public void abandon(Stage stage) throws IOException {
        synchronized (lock) {
            if (!stage.directory.exists()) { ACTIVE_STAGES.remove(stage.directory.toString()); return; }
            verifyOwner(stage); deleteTree(stage.directory); ACTIVE_STAGES.remove(stage.directory.toString()); sync.sync(staging);
        }
    }
    public List<Task> tasks(String accountId) throws IOException {
        synchronized (lock) {
            id(accountId); List<Task> found = new ArrayList<>();
            for (File directory : listFiles(tasks)) {
                if (!directory.getName().matches("[0-9a-f-]{36}")) continue;
                Task task = readTask(directory.getName()); if (accountId.equals(task.accountId)) found.add(task);
            }
            found.sort(Comparator.comparing(t -> t.taskId)); return Collections.unmodifiableList(found);
        }
    }
    public Task get(String accountId, String taskId) throws IOException {
        synchronized (lock) { Task t=readTask(taskId); require(t.accountId.equals(accountId),"ACCOUNT_MISMATCH"); return t; }
    }
    public Task bindProject(String accountId, String taskId, String serverProjectId) throws IOException {
        synchronized (lock) {
            Task t=get(accountId,taskId); id(serverProjectId);
            require(t.serverProjectId == null || t.serverProjectId.equals(serverProjectId),"PROJECT_MISMATCH");
            Map<String,Object> request=map("serverProjectId",serverProjectId,"releaseId",t.releaseId,"contentDigest",t.contentDigest,
                "expiryDays",t.expiryDays,"scene",HostedJson.parse(t.canonicalScene.getBytes(StandardCharsets.UTF_8),600*1024));
            Map<String,Object> m=taskMap(t); m.put("serverProjectId",serverProjectId); m.put("createRequest",request);
            return save(m);
        }
    }
    public Task recordUpload(String accountId, String taskId, Upload upload) throws IOException {
        synchronized (lock) {
            Task t=get(accountId,taskId); require(t.uploadId == null || t.uploadId.equals(upload.uploadId),"UPLOAD_MISMATCH");
            require(upload.releaseId == null || upload.releaseId.equals(t.releaseId),"RELEASE_MISMATCH");
            Map<String,Object> m=taskMap(t); m.put("uploadId",id(upload.uploadId)); m.put("state",upload.state); m.put("errorCode",upload.errorCode);
            if (upload.publication != null) { match(t,upload.publication); m.put("publication",publicationMap(upload.publication)); m.put("state","committed"); }
            else if (upload.state.equals("committed")) m.put("state","receipt_pending");
            return save(m);
        }
    }
    public Task recordPublication(String accountId, String taskId, Publication publication) throws IOException {
        synchronized (lock) {
            Task t=get(accountId,taskId); match(t,publication); Map<String,Object> m=taskMap(t);
            m.put("publication",publicationMap(publication)); m.put("state","committed"); m.put("errorCode",null); return save(m);
        }
    }
    public Task requestCancellation(String accountId, String taskId) throws IOException {
        synchronized (lock) { Map<String,Object> m=taskMap(get(accountId,taskId)); m.put("cancelRequested",true); return save(m); }
    }
    public Task recordState(String accountId, String taskId, String state, String errorCode) throws IOException {
        synchronized (lock) {
            Task t=get(accountId,taskId); Map<String,Object> m=taskMap(t);
            if (t.publication == null) m.put("state",state); m.put("errorCode",errorCode==null?null:safeCode(errorCode)); return save(m);
        }
    }
    public List<Asset> assets(Task task) { return Collections.unmodifiableList(assets(object(HostedJson.parse(task.canonicalScene.getBytes(StandardCharsets.UTF_8),600*1024)))); }
    public File assetFile(String accountId, String taskId, Asset asset) throws IOException {
        synchronized (lock) {
            Task t=get(accountId,taskId); Asset bound=assets(t).stream().filter(a -> a.id.equals(asset.id)).findFirst().orElseThrow(() -> new IllegalArgumentException("ASSET_NOT_DECLARED"));
            require(bound.path.equals(asset.path) && bound.sha256.equals(asset.sha256) && bound.byteLength==asset.byteLength && bound.mime.equals(asset.mime),"ASSET_MISMATCH");
            File file=resolveAsset(new File(child(tasks,taskId),"payload"),bound.path);
            require(file.length()==bound.byteLength && sha(file).equals(bound.sha256),"SNAPSHOT_ASSET_MISMATCH"); return file;
        }
    }
    /** Refreshes are explicitly account-scoped. A failed refresh never replaces a previous receipt. */
    public void cachePublications(String accountId, List<Publication> publications) throws IOException {
        synchronized (lock) {
            File dir=child(receipts,id(accountId)); mkdir(dir);
            for (Publication p:publications) {
                File revoked=new File(dir,id(p.publicationId)+".revoke.json");
                Map<String,Object> value=publicationMap(p);
                if(revoked.exists()) {
                    Map<String,Object> receipt=object(HostedJson.parse(read(revoked,4096),4096));
                    value.put("status","revoked");value.put("revokedAt",iso(string(receipt,"revokedAt")));
                }
                writeAtomic(new File(dir,p.publicationId+".json"),json(value));
                if("revoked".equals(value.get("status")))Files.deleteIfExists(new File(dir,p.publicationId+".pending.json").toPath());
            }
            sync.sync(dir);
        }
    }
    public List<Publication> cachedPublications(String accountId, String serverProjectId) throws IOException {
        synchronized (lock) {
            id(accountId); id(serverProjectId); File dir=child(receipts,accountId); List<Publication> out=new ArrayList<>();
            if (dir.exists()) for (File file:listFiles(dir)) {
                if (!file.getName().matches("[0-9a-f-]{36}\\.json")) continue;
                Publication p=applyRevocation(accountId,publication(object(HostedJson.parse(read(file,65536),65536))));
                if (p.serverProjectId.equals(serverProjectId)) out.add(p);
            }
            for (Task task:tasks(accountId)) if (task.publication!=null && task.publication.serverProjectId.equals(serverProjectId) && out.stream().noneMatch(p->p.publicationId.equals(task.publication.publicationId))) out.add(task.publication);
            return out;
        }
    }
    public void requestRevocation(String accountId, String publicationId) throws IOException {
        synchronized (lock) {
            File dir=child(receipts,id(accountId));mkdir(dir);
            writeAtomic(new File(dir,id(publicationId)+".pending.json"),json(map("publicationId",publicationId,"state","awaiting_server_confirmation")));
        }
    }
    public Set<String> pendingRevocations(String accountId) throws IOException {
        synchronized (lock) {
            File dir=child(receipts,id(accountId));Set<String> pending=new HashSet<>();if(!dir.exists())return pending;
            for(File file:listFiles(dir))if(file.getName().matches("[0-9a-f-]{36}\\.pending\\.json")) {
                Map<String,Object> value=object(HostedJson.parse(read(file,4096),4096));String publicationId=id(string(value,"publicationId"));
                require(file.getName().equals(publicationId+".pending.json"),"RECEIPT_MISMATCH");
                if(!new File(dir,publicationId+".revoke.json").exists())pending.add(publicationId);
            }
            return pending;
        }
    }
    public void recordRevocation(String accountId, Revocation revocation) throws IOException {
        synchronized (lock) {
            File dir=child(receipts,id(accountId)); mkdir(dir);
            // Keep the formal server acknowledgement independently of pagination/cache refresh.
            writeAtomic(new File(dir,id(revocation.publicationId)+".revoke.json"),json(map("publicationId",revocation.publicationId,
                "status","revoked","revokedAt",iso(revocation.revokedAt),"serverConfirmedAt",iso(revocation.serverConfirmedAt))));
            Files.deleteIfExists(new File(dir,revocation.publicationId+".pending.json").toPath());sync.sync(dir);
            File cache=new File(dir,revocation.publicationId+".json");
            if(cache.exists()) { Map<String,Object> m=object(HostedJson.parse(read(cache,65536),65536)); m.put("status","revoked");m.put("revokedAt",revocation.revokedAt);writeAtomic(cache,json(m)); }
            for(Task t:tasks(accountId)) if(t.publication!=null && t.publication.publicationId.equals(revocation.publicationId)) {
                Map<String,Object> p=publicationMap(t.publication);p.put("status","revoked");p.put("revokedAt",revocation.revokedAt);
                Map<String,Object> m=taskMap(t);m.put("publication",p);save(m);
            }
        }
    }
    private Task save(Map<String,Object> m) throws IOException {
        Task result=parseTask(object(HostedJson.parse(json(m),MAX_TASK_BYTES))); writeAtomic(new File(child(tasks,result.taskId),"task.json"),json(m)); return result;
    }
    private Task readTask(String taskId) throws IOException {
        File dir=child(tasks,id(taskId)); require(dir.isDirectory() && dir.getCanonicalFile().equals(dir),"TASK_NOT_FOUND");
        Task task=parseTask(object(HostedJson.parse(read(new File(dir,"task.json"),MAX_TASK_BYTES),MAX_TASK_BYTES)));
        require(task.taskId.equals(taskId),"TASK_MISMATCH");
        Map<String,Object> owner=object(HostedJson.parse(read(new File(dir,"owner.json"),4096),4096));
        require(owner.equals(owner(task.taskId,task.accountId,task.releaseId,task.contentDigest)),"OWNER_MISMATCH");
        if(task.publication!=null) {
            Publication observed=applyRevocation(task.accountId,task.publication);
            if(observed!=task.publication) {
                Map<String,Object> corrected=taskMap(task);corrected.put("publication",publicationMap(observed));
                task=parseTask(object(HostedJson.parse(json(corrected),MAX_TASK_BYTES)));
            }
        }
        return task;
    }
    private Publication applyRevocation(String accountId,Publication publication) throws IOException {
        File ack=new File(child(receipts,id(accountId)),id(publication.publicationId)+".revoke.json");
        if(!ack.exists())return publication;
        Map<String,Object> receipt=object(HostedJson.parse(read(ack,4096),4096));
        require(publication.publicationId.equals(string(receipt,"publicationId")) && "revoked".equals(string(receipt,"status")),"RECEIPT_MISMATCH");
        iso(string(receipt,"serverConfirmedAt"));Map<String,Object> value=publicationMap(publication);
        value.put("status","revoked");value.put("revokedAt",iso(string(receipt,"revokedAt")));return publication(value);
    }
    private static Task parseTask(Map<String,Object> m) {
        require(number(m.get("version"))==1 && ENDPOINT.equals(string(m,"endpoint")),"UNSUPPORTED_TASK");
        String scene=new String(json(m.get("scene")),StandardCharsets.UTF_8);
        String release=id(string(m,"releaseId")), digest=digest(string(m,"contentDigest"));
        require(sha(scene.getBytes(StandardCharsets.UTF_8)).equals(digest) && release.equals(string(object(m.get("scene")),"releaseId")),"SNAPSHOT_SCENE_MISMATCH");
        String project=nullableString(m,"serverProjectId"), upload=nullableString(m,"uploadId"); if(project!=null)id(project);if(upload!=null)id(upload);
        require(m.get("cancelRequested") instanceof Boolean,"INVALID_TASK"); int days=integer(m.get("expiryDays"));require(Arrays.asList(1,7,30).contains(days),"INVALID_EXPIRY");
        Task t=new Task(id(string(m,"taskId")),ENDPOINT,id(string(m,"accountId")),id(string(m,"localProjectId")),release,digest,string(m,"title"),
            number(m.get("projectRevision")),number(m.get("byteLength")),days,integer(m.get("assetCount")),scene,
            digest(string(m,"fileListDigest")),id(string(m,"createKey")),id(string(m,"commitKey")),
            m.get("createRequest")==null?null:new String(json(m.get("createRequest")),StandardCharsets.UTF_8),
            new String(json(m.get("commitRequest")),StandardCharsets.UTF_8),project,upload,string(m,"state"),(Boolean)m.get("cancelRequested"),
            nullableString(m,"errorCode"),m.get("publication")==null?null:publication(object(m.get("publication"))));
        Map<String,Object> commit=object(m.get("commitRequest"));require(commit.equals(map("releaseId",release,"contentDigest",digest)),"REQUEST_MISMATCH");
        if(t.createRequest!=null)require(object(m.get("createRequest")).equals(map("serverProjectId",project,"releaseId",release,"contentDigest",digest,"expiryDays",new java.math.BigDecimal(days),"scene",m.get("scene"))),"REQUEST_MISMATCH");
        if(t.publication!=null)match(t,t.publication); return t;
    }
    private static Map<String,Object> taskMap(Task t) {
        return map("version",1,"taskId",t.taskId,"endpoint",t.endpoint,"accountId",t.accountId,"localProjectId",t.localProjectId,
            "releaseId",t.releaseId,"contentDigest",t.contentDigest,"title",t.title,"projectRevision",t.projectRevision,"byteLength",t.byteLength,
            "expiryDays",t.expiryDays,"assetCount",t.assetCount,"scene",HostedJson.parse(t.canonicalScene.getBytes(StandardCharsets.UTF_8),600*1024),
            "fileListDigest",t.fileListDigest,"createKey",t.createKey,"commitKey",t.commitKey,
            "createRequest",t.createRequest==null?null:HostedJson.parse(t.createRequest.getBytes(StandardCharsets.UTF_8),650*1024),
            "commitRequest",HostedJson.parse(t.commitRequest.getBytes(StandardCharsets.UTF_8),4096),"serverProjectId",t.serverProjectId,
            "uploadId",t.uploadId,"state",t.state,"cancelRequested",t.cancelRequested,"errorCode",t.errorCode,
            "publication",t.publication==null?null:publicationMap(t.publication));
    }
    private static void match(Task t, Publication p) {
        require(t.serverProjectId!=null && t.serverProjectId.equals(p.serverProjectId) && t.releaseId.equals(p.releaseId) &&
            t.contentDigest.equals(p.contentDigest) && t.title.equals(p.title),"PUBLICATION_MISMATCH");
        if(t.publication!=null) require(t.publication.publicationId.equals(p.publicationId),"PUBLICATION_MISMATCH");
    }
    private static List<Asset> assets(Map<String,Object> scene) {
        List<Asset> out=new ArrayList<>();Set<String> ids=new HashSet<>(), paths=new HashSet<>();
        for(Object value:list(scene,"assets")) {
            Map<String,Object> a=object(value);String assetId=id(string(a,"id")),path=string(a,"path"),mime=string(a,"mime");long length=number(a.get("byteLength"));
            require(path.matches("assets/[A-Za-z0-9._-]+\\.(png|mp4)") && !path.contains("..") && length>0 && length<=50L*1024*1024 &&
                (mime.equals("image/png") && path.endsWith(".png") || mime.equals("video/mp4") && path.endsWith(".mp4")) && ids.add(assetId) && paths.add(path),"INVALID_ASSET");
            out.add(new Asset(assetId,path,mime,digest(string(a,"sha256")),length));
        }
        require(out.size()<=200,"ASSET_LIMIT");return out;
    }
    private void verifyPayload(File payload, Task task) throws IOException {
        require(payload.getCanonicalFile().equals(payload),"UNSAFE_PAYLOAD"); Set<String> expected=new HashSet<>();expected.add("scene.json");
        for(Asset a:assets(task)) { expected.add(a.path);File file=resolveAsset(payload,a.path);require(file.length()==a.byteLength && sha(file).equals(a.sha256),"SNAPSHOT_ASSET_MISMATCH"); }
        Set<String> actual=new HashSet<>(); collectFiles(payload,payload,actual); require(expected.equals(actual),"UNEXPECTED_PAYLOAD_FILE");
    }
    private void collectFiles(File root, File current, Set<String> files) throws IOException {
        for(File file:listFiles(current)) {
            require(file.getCanonicalFile().equals(file) && !Files.isSymbolicLink(file.toPath()),"UNSAFE_PAYLOAD");
            if(file.isDirectory()) { require(file.equals(new File(root,"assets")),"UNEXPECTED_PAYLOAD_DIRECTORY");collectFiles(root,file,files); }
            else { require(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS),"UNSAFE_PAYLOAD");files.add(root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/')); }
        }
    }
    private File resolveAsset(File payload,String path) throws IOException { File file=new File(payload,path);require(file.getCanonicalFile().equals(file) && file.toPath().startsWith(payload.toPath()) && Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS),"UNSAFE_ASSET_PATH");return file; }
    private void verifyOwner(Stage stage) throws IOException {
        require(stage.directory.equals(child(staging,stage.taskId)) && stage.directory.getCanonicalFile().equals(stage.directory),"OWNER_MISMATCH");
        require(object(HostedJson.parse(read(new File(stage.directory,"owner.json"),4096),4096)).equals(owner(stage)),"OWNER_MISMATCH");
    }
    private static Map<String,Object> owner(Stage s) { return owner(s.taskId,s.accountId,s.releaseId,s.contentDigest); }
    private static Map<String,Object> owner(String task,String account,String release,String digest) { return map("owner","tapscene-hosted-stage-v1","taskId",task,"endpoint",ENDPOINT,"accountId",account,"releaseId",release,"contentDigest",digest); }
    private void recoverStaging() throws IOException {
        for(File dir:listFiles(staging)) {
            if(ACTIVE_STAGES.contains(dir.toString()) || !dir.getName().matches("[0-9a-f-]{36}"))continue;
            try {
                Map<String,Object> m=object(HostedJson.parse(read(new File(dir,"owner.json"),4096),4096));
                Stage s=new Stage(id(dir.getName()),id(string(m,"accountId")),id(string(m,"releaseId")),digest(string(m,"contentDigest")),dir);
                verifyOwner(s);deleteTree(dir);
            } catch(IllegalArgumentException | FileNotFoundException e) { /* Unowned/corrupt paths are quarantined, never guessed away. */ }
        }
        sync.sync(staging);
    }
    private void syncTree(File directory) throws IOException { for(File file:listFiles(directory))if(file.isDirectory())syncTree(file);else try(FileOutputStream out=new FileOutputStream(file,true)){out.getFD().sync();}sync.sync(directory); }
    private void writeAtomic(File file,byte[] bytes) throws IOException {
        require(file.getCanonicalFile().equals(file),"UNSAFE_STORE_PATH"); File temp=new File(file.getParentFile(),file.getName()+"."+UUID.randomUUID()+".tmp");
        try { try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.sync(file.getParentFile());
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private static byte[] read(File file,int max) throws IOException { require(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS) && file.getCanonicalFile().equals(file) && file.length()<=max,"INVALID_STORE_FILE");byte[] bytes=Files.readAllBytes(file.toPath());require(bytes.length<=max,"INVALID_STORE_FILE");return bytes; }
    private static void mkdir(File file) throws IOException { require(file.getCanonicalFile().equals(file),"UNSAFE_STORE_PATH");if(!file.isDirectory()&&!file.mkdirs())throw new IOException("STORE_DIRECTORY_FAILED"); }
    private static File child(File root,String name) throws IOException { id(name);File file=new File(root,name);require(file.getCanonicalFile().equals(file),"UNSAFE_STORE_PATH");return file; }
    private static File[] listFiles(File directory) throws IOException { File[] files=directory.listFiles();if(files==null)throw new IOException("STORE_READ_FAILED");return files; }
    private static void deleteTree(File file) throws IOException {
        require(file.getCanonicalFile().equals(file) && !Files.isSymbolicLink(file.toPath()),"UNSAFE_CLEANUP_PATH");
        if(file.isDirectory())for(File child:listFiles(file))deleteTree(child);Files.delete(file.toPath());
    }
}
