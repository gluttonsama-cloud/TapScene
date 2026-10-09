import com.tapscene.packageformat.AiPackageCodec;
import com.tapscene.packageformat.RenderPlan;
import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Synthetic UI images are authored test content, not user recordings or privacy approval. */
public final class AiPackageChecks {
    private static final ViewerPackageCodec.CancelCheck NO_CANCEL = () -> {};
    private static int checks;
    private AiPackageChecks() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a new fixture directory");
        Path root = Files.createDirectory(Path.of(args[0])), assets = Files.createDirectory(root.resolve("source"));
        Files.createDirectory(assets.resolve("assets"));
        List<ViewerScene.Asset> media = new ArrayList<>();
        BufferedImage home = ui("Discover your next idea", "Design systems", "View collection", "Finish demo", false);
        BufferedImage detail = ui("Collection details", "Quiet, useful interfaces", "Save this collection", "Return to discover", false);
        BufferedImage ending = ui("You are all set", "Saved to your workspace", "Demo complete", "Synthetic sample", true);
        media.add(png(assets,id(21),home,ViewerScene.Asset.ROLE_IMAGE));
        media.add(png(assets,id(22),detail,ViewerScene.Asset.ROLE_IMAGE));
        media.add(png(assets,id(23),ending,ViewerScene.Asset.ROLE_IMAGE));
        media.add(png(assets,id(24),home.getSubimage(36,240,468,230),ViewerScene.Asset.ROLE_REGION_CROP));
        media.add(png(assets,id(25),detail.getSubimage(36,240,468,230),ViewerScene.Asset.ROLE_REGION_CROP));
        ViewerScene scene = new ViewerScene(3,ViewerPackageCodec.REGION_POLICY_VERSION,ViewerPackageCodec.COMPILER_VERSION,id(1),
                "Synthetic product walkthrough", "Authored test UI; not a user recording",1700000000000L,id(11),
                List.of(state(11,21,"Discover",false),state(12,22,"Collection",false),state(13,23,"Complete",true)),
                List.of(edge(41,11,12,31),edge(42,11,13,32),new ViewerScene.Edge(id(43),id(12),id(11),null,null,"Return to discover","continue","authored"),edge(44,12,13,33)),
                List.of(spot(31,11,"View collection",.1,.56,.8,.1),spot(32,11,"Finish demo",.1,.7,.8,.1),spot(33,12,"Save collection",.1,.56,.8,.1)),
                List.of(region(51,11,21,24),region(52,12,22,25)),media);
        List<RenderPlan.Visit> visits=List.of(visit(61,11,41,75),visit(62,12,43,60),visit(63,11,42,60),visit(64,13,null,75));
        List<RenderPlan.Effect> effects=List.of(
                effect("focus",61,5,40,null,51,null,null),
                effect("click",61,50,15,31,null,null,null),
                effect("transition",61,67,8,null,null,null,null),
                effect("highlight",62,10,40,null,52,null,null),
                effect("annotation",63,4,40,null,null,"Return visits keep a distinct identity",new ViewerScene.Rect(.08,.15,.84,.12)),
                effect("click",63,45,15,32,null,null,null));
        RenderPlan portrait=RenderPlan.build(scene,1080,1920,visits,effects), landscape=RenderPlan.build(scene,1920,1080,visits,effects);
        require(portrait.totalFrames==262,"Crossfade accounting differs");
        byte[] sealed=ViewerPackageCodec.writeScene(scene);
        Path portraitZip=root.resolve("synthetic-portrait.tapscene-ai"), landscapeZip=root.resolve("synthetic-landscape.tapscene-ai");
        AiPackageCodec.writePackage(scene,portrait,assets.toFile(),portraitZip.toFile(),NO_CANCEL,null);
        AiPackageCodec.writePackage(scene,landscape,assets.toFile(),landscapeZip.toFile(),NO_CANCEL,null);
        Path portraitRoot=Files.createDirectory(root.resolve("portrait")), landscapeRoot=Files.createDirectory(root.resolve("landscape"));
        AiPackageCodec.LoadedPackage loaded=AiPackageCodec.readPackage(portraitZip.toFile(),portraitRoot.toFile(),NO_CANCEL,null);
        AiPackageCodec.readPackage(landscapeZip.toFile(),landscapeRoot.toFile(),NO_CANCEL,null);
        require(Arrays.equals(sealed,Files.readAllBytes(portraitRoot.resolve("scene.json"))) && Arrays.equals(sealed,Files.readAllBytes(landscapeRoot.resolve("scene.json"))),"Changing canvas changed sealed scene");
        require(loaded.scene.edges.size()==4 && loaded.scene.assets.size()==5 && loaded.renderPlan.visits.size()==4,"Full graph or crop media disappeared");
        require(loaded.renderPlan.visits.get(0).stateId.equals(loaded.renderPlan.visits.get(2).stateId) && !loaded.renderPlan.visits.get(0).visitId.equals(loaded.renderPlan.visits.get(2).visitId),"Finite revisit identity lost");
        rejected("duplicate visit identity",()->RenderPlan.build(scene,1080,1920,List.of(visits.get(0),visits.get(1),visit(61,11,42,60),visits.get(3)),effects));
        rejected("wrong branch destination",()->RenderPlan.build(scene,1080,1920,List.of(visit(61,11,41,60),visit(62,13,null,60)),List.of()));
        rejected("unfinished path",()->RenderPlan.build(scene,1080,1920,List.of(visit(61,11,null,60)),List.of()));
        rejected("wrong start",()->RenderPlan.build(scene,1080,1920,List.of(visit(61,13,null,60)),List.of()));
        rejected("unsupported canvas",()->RenderPlan.build(scene,540,960,visits,List.of()));
        rejected("zero hold",()->RenderPlan.build(scene,1080,1920,List.of(visit(61,11,42,0),visit(62,13,null,60)),List.of()));
        rejected("outside visit effect",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("highlight",61,70,10,null,null,null,new ViewerScene.Rect(0,0,1,1)))));
        rejected("unknown dynamic effect",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("script",61,0,10,null,null,"alert(1)",null))));
        rejected("cross-state region",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("focus",61,0,10,null,52,null,null))));
        rejected("incorrect click hotspot",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("click",61,0,10,32,null,null,null))));
        rejected("crossfade at end",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("transition",64,65,10,null,null,null,null))));
        rejected("crossfade consumes whole hold",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("transition",61,0,75,null,null,null,null))));
        rejected("adjacent crossfades exceed layer budget",()->RenderPlan.build(scene,1080,1920,visits,List.of(
                effect("transition",61,25,50,null,null,null,null),
                effect("transition",62,10,50,null,null,null,null))));
        String json=new String(portrait.toBytes(),StandardCharsets.UTF_8);
        rejected("tampered timeline",()->RenderPlan.parse(scene,json.replace("\"totalFrames\":262","\"totalFrames\":263").getBytes(StandardCharsets.UTF_8)));
        rejected("stale release binding",()->RenderPlan.parse(scene,json.replace(scene.releaseId,id(999)).getBytes(StandardCharsets.UTF_8)));
        rejected("extra expression field",()->RenderPlan.parse(scene,json.replace("\"fps\":30","\"fps\":30,\"expression\":\"fetch('x')\"").getBytes(StandardCharsets.UTF_8)));
        rejected("duplicate plan key",()->RenderPlan.parse(scene,json.replace("\"fps\":30","\"fps\":30,\"fps\":30").getBytes(StandardCharsets.UTF_8)));
        List<RenderPlan.Visit> longPath=new ArrayList<>();
        for(int i=0;i<12;i++) longPath.add(visit(100+i,i%2==0?11:12,i%2==0?41:43,1800));
        longPath.add(visit(120,11,42,1800)); longPath.add(visit(121,13,null,1800));
        rejected("total frame budget",()->RenderPlan.build(scene,1080,1920,longPath,List.of()));
        Path bad=root.resolve("undeclared.zip"); extraEntry(portraitZip,bad,"evil-script.js","alert(1)".getBytes(StandardCharsets.UTF_8));
        Path isolated=Files.createDirectory(root.resolve("rejected"));
        rejected("undeclared executable entry",()->AiPackageCodec.readPackage(bad.toFile(),isolated.toFile(),NO_CANCEL,null));
        require(isolated.toFile().list().length==0,"Rejected package was not cleared");
        Path traversal=root.resolve("traversal.zip"); extraEntry(portraitZip,traversal,"../outside.png",new byte[]{1});
        rejected("path traversal",()->AiPackageCodec.readPackage(traversal.toFile(),isolated.toFile(),NO_CANCEL,null));
        Path external=root.resolve("external.zip"); extraEntry(portraitZip,external,"https://example.com/a.png",new byte[]{1});
        rejected("external media URL",()->AiPackageCodec.readPackage(external.toFile(),isolated.toFile(),NO_CANCEL,null));
        Path cancelled=root.resolve("cancelled.tapscene-ai");
        rejected("cancelled export",()->AiPackageCodec.writePackage(scene,portrait,assets.toFile(),cancelled.toFile(),()->{throw new IllegalStateException("cancelled");},null));
        require(!Files.exists(cancelled),"Cancelled export leaked output");
        // A different plan can omit the collection branch but must keep its state and media.
        RenderPlan shortPlan=RenderPlan.build(scene,1080,1920,List.of(visit(81,11,42,60),visit(82,13,null,60)),List.of());
        Path shortZip=root.resolve("synthetic-short.tapscene-ai"); AiPackageCodec.writePackage(scene,shortPlan,assets.toFile(),shortZip.toFile(),NO_CANCEL,null);
        Path shortRoot=Files.createDirectory(root.resolve("short"));
        require(AiPackageCodec.readPackage(shortZip.toFile(),shortRoot.toFile(),NO_CANCEL,null).scene.states.size()==3,"Unused branch was pruned");
        rejected("zero rounded video frames",()->RenderPlan.millisecondsToFrames(1));
        require(RenderPlan.millisecondsToFrames(17)==1 && RenderPlan.millisecondsToFrames(1000)==30,"Duration rounding differs");
        List<ViewerScene.Edge> endEdges=new ArrayList<>(scene.edges);
        endEdges.set(1,new ViewerScene.Edge(id(42),id(11),null,"Finish synthetic demo",id(32),"Finish demo","tap","authored"));
        ViewerScene endScene=new ViewerScene(3,scene.policyVersion,scene.compilerVersion,id(2),scene.title,scene.goal,scene.createdAt,scene.startStateId,scene.states,endEdges,scene.hotspots,scene.regions,scene.assets);
        require(RenderPlan.build(endScene,1080,1920,List.of(visit(81,11,42,60)),List.of()).totalFrames==60,"Explicit end edge was not accepted");
        makeVideoFixture(root,scene,visits,effects);
        Files.writeString(root.resolve("fixture-note.txt"),"All artwork in these fixtures is synthetic authored UI, not user recordings.\n",StandardCharsets.UTF_8);
        System.out.println("TAPSCENE_AI_PACKAGE_CHECKS_PASSED: "+checks+" assertions/rejections; fixtures="+root);
    }
    private static void makeVideoFixture(Path root,ViewerScene original,List<RenderPlan.Visit> visits,List<RenderPlan.Effect> effects) throws Exception {
        Path source=Files.createDirectory(root.resolve("video-source")); Files.createDirectory(source.resolve("assets"));
        for(ViewerScene.Asset asset:original.assets) Files.copy(root.resolve("source").resolve(asset.path),source.resolve(asset.path));
        Path video=source.resolve("assets/"+id(26)+".mp4");
        Process process=new ProcessBuilder("ffmpeg","-hide_banner","-loglevel","error","-nostdin","-loop","1","-i",source.resolve(original.assets.get(1).path).toString(),
                "-vf","fade=t=in:st=0:d=0.3,fade=t=out:st=0.7:d=0.3","-frames:v","30","-r","30","-c:v","libx264","-pix_fmt","yuv420p","-color_primaries","bt709","-color_trc","bt709","-colorspace","bt709",
                "-an","-map_metadata","-1","-movflags","+faststart",video.toString()).redirectErrorStream(true).start();
        byte[] log=process.getInputStream().readAllBytes();
        require(process.waitFor()==0,"Synthetic video generation failed: "+new String(log,StandardCharsets.UTF_8));
        List<ViewerScene.Asset> media=new ArrayList<>(original.assets);
        media.add(new ViewerScene.Asset(id(26),"assets/"+id(26)+".mp4","video/mp4",Files.size(video),ViewerPackageCodec.sha256(video.toFile()),540,960,ViewerScene.Asset.ROLE_TRANSITION,1000L));
        List<ViewerScene.Edge> edges=new ArrayList<>(original.edges); ViewerScene.Edge old=edges.get(2);
        edges.set(2,new ViewerScene.Edge(old.id,old.fromStateId,old.toStateId,old.endLabel,old.hotspotId,old.label,old.trigger,old.sourceKind,id(26)));
        ViewerScene scene=new ViewerScene(3,original.policyVersion,original.compilerVersion,id(3),original.title+" with video",original.goal,original.createdAt,original.startStateId,original.states,edges,original.hotspots,original.regions,media);
        RenderPlan plan=RenderPlan.build(scene,1080,1920,visits,effects);
        require(plan.totalFrames==292 && plan.timeline.get(1).transitionFrames==30,"Video frames were not derived once");
        Path zip=root.resolve("synthetic-video.tapscene-ai"),destination=Files.createDirectory(root.resolve("video")); HostVideoValidator validator=new HostVideoValidator();
        AiPackageCodec.writePackage(scene,plan,source.toFile(),zip.toFile(),NO_CANCEL,validator);
        rejected("AI video import without full decoder",()->AiPackageCodec.readPackage(zip.toFile(),destination.toFile(),NO_CANCEL,null));
        require(destination.toFile().list().length==0,"Missing-decoder import left files");
        require(AiPackageCodec.readPackage(zip.toFile(),destination.toFile(),NO_CANCEL,validator).renderPlan.totalFrames==292 && validator.decodedFiles()>=2,"Video AI package did not fully decode");
        rejected("video plus synthetic overlap",()->RenderPlan.build(scene,1080,1920,visits,List.of(effect("transition",62,52,8,null,null,null,null))));
    }
    private static ViewerScene.State state(int id,int asset,String title,boolean terminal) {return new ViewerScene.State(id(id),id(asset),540,960,title,"Synthetic authored example","authored",terminal);}
    private static ViewerScene.Edge edge(int id,int from,int to,int hotspot) {return new ViewerScene.Edge(id(id),id(from),id(to),null,id(hotspot),"Open "+to,"tap","authored");}
    private static ViewerScene.Hotspot spot(int id,int state,String label,double x,double y,double w,double h) {return new ViewerScene.Hotspot(id(id),id(state),label,new ViewerScene.Rect(x,y,w,h));}
    private static ViewerScene.Region region(int id,int state,int base,int asset) {return new ViewerScene.Region(id(id),id(state),id(base),id(asset),"Visible feature card",540,960,new ViewerScene.PixelRect(36,240,468,230),"cards",2,new ViewerScene.Anchor(.5,.5));}
    private static RenderPlan.Visit visit(int id,int state,Integer edge,int hold) {return new RenderPlan.Visit(id(id),id(state),edge==null?null:id(edge),hold);}
    private static RenderPlan.Effect effect(String type,int visit,int start,int duration,Integer hotspot,Integer region,String text,ViewerScene.Rect rect) {return new RenderPlan.Effect(type,id(visit),start,duration,hotspot==null?null:id(hotspot),region==null?null:id(region),text,rect);}
    private static String id(int number) {return String.format("00000000-0000-4000-8000-%012d",number);}
    private static ViewerScene.Asset png(Path root,String id,BufferedImage source,String role) throws IOException {
        BufferedImage image=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics(); g.drawImage(source,0,0,null); g.dispose();
        Path file=root.resolve("assets/"+id+".png"); if(!ImageIO.write(image,"png",file.toFile())) throw new IOException("PNG encoder missing");
        return new ViewerScene.Asset(id,"assets/"+id+".png","image/png",Files.size(file),ViewerPackageCodec.sha256(file.toFile()),image.getWidth(),image.getHeight(),role,null);
    }
    private static BufferedImage ui(String title,String card,String action,String secondary,boolean done) {
        BufferedImage image=new BufferedImage(540,960,BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(new Color(245,247,249)); g.fillRect(0,0,540,960);
        g.setColor(new Color(24,39,51)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20)); g.drawString("TAPSCENE / SYNTHETIC DEMO",36,55);
        g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,30)); g.drawString(title,36,151); g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,18)); g.setColor(new Color(95,110,118)); g.drawString("A small flow, with a choice and a return.",36,190);
        g.setColor(new Color(225,235,230)); g.fillRoundRect(36,240,468,230,24,24); g.setColor(new Color(44,110,88)); g.fillOval(70,278,64,64);
        g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,32)); g.drawString(done?"✓":"+",87,322); g.setColor(new Color(24,65,53)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,23)); g.drawString(card,65,393);
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,17)); g.drawString("Visible pixels, preserved in a safe crop",65,434);
        g.setColor(new Color(28,90,71)); g.fillRoundRect(36,530,468,88,18,18); g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,22)); g.drawString(action,62,584);
        g.setColor(new Color(221,228,232)); g.fillRoundRect(36,670,468,88,18,18); g.setColor(new Color(38,60,73)); g.drawString(secondary,62,724);
        g.setColor(new Color(112,124,132)); g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,17)); g.drawString("Authored fixture • no personal information",36,894); g.dispose(); return image;
    }
    private static void extraEntry(Path source,Path target,String name,byte[] bytes) throws IOException {
        try(ZipFile in=new ZipFile(source.toFile()); ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(target))) {
            var entries=in.entries(); while(entries.hasMoreElements()) {ZipEntry entry=entries.nextElement(); stored(out,entry.getName(),in.getInputStream(entry).readAllBytes());} stored(out,name,bytes);
        }
    }
    private static void stored(ZipOutputStream out,String name,byte[] bytes) throws IOException {ZipEntry entry=new ZipEntry(name);entry.setMethod(ZipEntry.STORED);entry.setSize(bytes.length);CRC32 crc=new CRC32();crc.update(bytes);entry.setCrc(crc.getValue());out.putNextEntry(entry);out.write(bytes);out.closeEntry();}
    private static void require(boolean value,String reason) {checks++;if(!value)throw new AssertionError(reason);}
    private interface Checked {void run() throws Exception;}
    private static void rejected(String label,Checked action) throws Exception {try{action.run();}catch(IllegalArgumentException|IllegalStateException|IOException expected){checks++;return;}throw new AssertionError("Accepted "+label);}
}
