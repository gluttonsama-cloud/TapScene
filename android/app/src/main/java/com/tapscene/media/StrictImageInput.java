package com.tapscene.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Bounded, Android-independent input validation. BitmapFactory may return a partial bitmap on
 * API 26, so success from that decoder is deliberately not used as the completeness check.
 * Every PNG zlib byte / row and every JPEG Huffman-coded MCU in every scan is consumed here.
 * No EXIF thumbnail, gainmap, unknown metadata or additional image enters the platform decoder.
 * Only ordinary static PNG and 8-bit Huffman baseline/progressive grayscale / RGB JPEG qualify.
 */
public final class StrictImageInput {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final long MAX_PIXELS = 12_000_000L;
    public static final int MAX_DIMENSION = 32_768;
    private static final int MAX_ICC_BYTES = 1024 * 1024;
    private StrictImageInput() {}

    public static final class Result {
        public final String mime;
        public final int width, height, orientation;
        public final byte[] decodeBytes;
        private Result(String mime, int width, int height, int orientation, byte[] decodeBytes) {
            this.mime = mime; this.width = width; this.height = height;
            this.orientation = orientation; this.decodeBytes = decodeBytes;
        }
    }
    public static Result inspect(byte[] bytes) throws IOException {
        return inspect(bytes, () -> {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        });
    }
    public static Result inspect(byte[] bytes, Runnable checkpoint) throws IOException {
        checkpoint.run();
        need(bytes.length > 0 && bytes.length <= MAX_BYTES, "截图为空或超过 10 MiB。");
        // Input is already counted in used heap. Reserve two sanitized copies plus parser state.
        requireMemoryHeadroom(2L * bytes.length + 24L * 1024 * 1024);
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8),
                new byte[]{(byte)137,80,78,71,13,10,26,10})) return png(bytes, checkpoint);
        if (bytes.length >= 2 && u(bytes, 0) == 255 && u(bytes, 1) == 216)
            return new Jpeg(bytes, checkpoint).parse();
        throw bad("请选择真实的 PNG 或 JPEG 静态截图。");
    }
    private static void dimensions(int w, int h) throws IOException {
        need(w > 0 && h > 0 && (long)w * h <= MAX_PIXELS, "截图不能超过 1200 万像素。");
        need(w <= MAX_DIMENSION && h <= MAX_DIMENSION, "截图单边不能超过 32768 像素。");
    }
    private static Result png(byte[] a, Runnable check) throws IOException {
        ByteArrayOutputStream clean = new ByteArrayOutputStream(a.length);
        clean.write(a, 0, 8);
        int p = 8, w = 0, h = 0, depth = 0, color = -1, interlace = 0, orientation = 1;
        boolean ihdr = false, plte = false, idat = false, dataEnded = false, end = false, exif = false, transparency = false;
        int paletteSize = 0;
        List<int[]> compressed = new ArrayList<>();
        java.util.HashSet<String> singleton = new java.util.HashSet<>();
        while (p < a.length) {
            check.run();
            need(a.length - p >= 12, "PNG 数据块不完整。");
            long length = be32(a, p);
            need(length <= a.length - p - 12L, "PNG 数据块长度无效。");
            int n = (int)length, d = p + 8;
            String type = new String(a, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            for (int i = p + 4; i < p + 8; i++) need((u(a,i)>=65 && u(a,i)<=90) || (u(a,i)>=97 && u(a,i)<=122), "PNG 数据块类型无效。");
            need((u(a,p+6)&32)==0, "PNG 保留位无效。");
            CRC32 crc = new CRC32(); crc.update(a, p + 4, n + 4);
            need(crc.getValue() == be32(a, d + n), "PNG 校验失败，文件可能损坏。");
            need(ihdr || type.equals("IHDR"), "PNG 缺少图像头。");
            need(!end, "PNG 结尾存在额外内容。");
            boolean retain = false;
            switch (type) {
                case "IHDR":
                    need(!ihdr && p == 8 && n == 13, "PNG 图像头无效。");
                    w = positiveInt(a,d); h = positiveInt(a,d+4); dimensions(w,h);
                    depth=u(a,d+8); color=u(a,d+9); interlace=u(a,d+12);
                    need((color==0 && (depth==1||depth==2||depth==4||depth==8||depth==16)) ||
                        (color==2 && (depth==8||depth==16)) || (color==3 && (depth==1||depth==2||depth==4||depth==8)) ||
                        ((color==4||color==6) && (depth==8||depth==16)), "不支持此 PNG 像素格式。");
                    need(u(a,d+10)==0 && u(a,d+11)==0 && interlace<=1, "PNG 编码参数无效。");
                    ihdr=true; retain=true; break;
                case "PLTE":
                    need(!plte && !idat && n>0 && n<=768 && n%3==0 && color!=0 && color!=4, "PNG 调色板无效。");
                    paletteSize=n/3;
                    need(color!=3 || paletteSize <= (1<<depth), "PNG 调色板过大。");
                    plte=true; retain=true; break;
                case "tRNS":
                    need(!transparency && !idat && ((color==0&&n==2)||(color==2&&n==6)||(color==3&&plte&&n>0&&n<=paletteSize)), "PNG 透明度数据无效。");
                    transparency=true; retain=true; break;
                case "IDAT":
                    need(!dataEnded && (color!=3 || plte), "PNG 像素数据顺序无效。");
                    idat=true; compressed.add(new int[]{d,n}); retain=true; break;
                case "IEND":
                    need(n==0 && idat && d+4==a.length, "PNG 不完整或包含额外图像。");
                    end=true; retain=true; break;
                case "acTL": case "fcTL": case "fdAT":
                    throw bad("暂不支持动画 PNG，请选择单张静态截图。");
                case "eXIf":
                    need(!exif && !idat, "PNG EXIF 数据无效。");
                    orientation=exif(a,d,n); exif=true; break;
                case "iCCP":
                    need(!idat && singleton.add(type) && !singleton.contains("sRGB"), "PNG 颜色配置重复或顺序无效。");
                    int z=d; while(z<d+n && a[z]!=0) z++;
                    need(z>d && z-d<=79 && z+2<d+n && a[z+1]==0, "PNG 颜色配置无效。");
                    validateIcc(inflateBounded(a,z+2,d+n-z-2,check)); retain=true; break;
                case "sRGB":
                    need(!idat && singleton.add(type) && !singleton.contains("iCCP") && n==1 && u(a,d)<=3, "PNG sRGB 配置无效。");
                    retain=true; break;
                case "gAMA":
                    need(!idat && singleton.add(type) && n==4 && be32(a,d)>0, "PNG gamma 配置无效。"); retain=true; break;
                case "cHRM":
                    need(!idat && singleton.add(type) && n==32, "PNG 颜色坐标无效。"); retain=true; break;
                default:
                    need((u(a,p+4)&32)!=0, "不支持此 PNG 必需数据块。");
                    // Unknown ancillary metadata is private original-only, never decoder input.
            }
            if(idat && !type.equals("IDAT")) dataEnded=true;
            if(retain) clean.write(a,p,n+12);
            p += n+12;
        }
        need(end, "PNG 文件缺少完整结尾。");
        int channels = color==0||color==3 ? 1 : color==2 ? 3 : color==4 ? 2 : 4;
        validatePngRaster(a,compressed,w,h,channels*depth,interlace,color==3?paletteSize:0,check);
        check.run();
        return new Result("image/png",w,h,orientation,clean.toByteArray());
    }

    private static void validatePngRaster(byte[] a, List<int[]> spans, int w, int h, int bpp, int interlace, int paletteSize, Runnable check) throws IOException {
        int[] xs=interlace==0 ? new int[]{0} : new int[]{0,4,0,2,0,1,0};
        int[] ys=interlace==0 ? new int[]{0} : new int[]{0,0,4,0,2,0,1};
        int[] dx=interlace==0 ? new int[]{1} : new int[]{8,8,4,4,2,2,1};
        int[] dy=interlace==0 ? new int[]{1} : new int[]{8,8,8,4,4,2,2};
        List<long[]> passes=new ArrayList<>(); long expected=0;
        for(int i=0;i<xs.length;i++) {
            long pw=w<=xs[i]?0:((long)w-xs[i]+dx[i]-1)/dx[i];
            long ph=h<=ys[i]?0:((long)h-ys[i]+dy[i]-1)/dy[i];
            if(pw>0 && ph>0) { long stride=(pw*bpp+7)/8+1; passes.add(new long[]{stride,ph,pw}); expected+=stride*ph; }
        }
        Inflater inflater=new Inflater(); byte[] buffer=new byte[32*1024];
        int span=0, pass=0, filter=0; long total=0, rowOffset=0, row=0;
        byte[] previous = paletteSize>0 ? new byte[(int)passes.get(0)[0]-1] : null;
        byte[] current = paletteSize>0 ? new byte[previous.length] : null;
        try {
            while(!inflater.finished()) {
                check.run();
                if(inflater.needsInput()) {
                    while(span<spans.size() && spans.get(span)[1]==0) span++;
                    need(span<spans.size(), "PNG 像素数据被截断。");
                    int[] s=spans.get(span++); inflater.setInput(a,s[0],s[1]);
                }
                int count=inflater.inflate(buffer);
                need(!inflater.needsDictionary(), "PNG 使用了不允许的压缩字典。");
                need(count>0 || inflater.finished() || inflater.needsInput(), "PNG 压缩数据损坏。");
                need(total+count<=expected, "PNG 解压后的像素尺寸不符。");
                int cursor=0;
                while(cursor<count) {
                    need(pass<passes.size(), "PNG 像素数量过多。");
                    long[] shape=passes.get(pass);
                    if(rowOffset==0) {filter=u(buffer,cursor);need(filter<=4, "PNG 行过滤器无效。");}
                    int take=(int)Math.min(count-cursor,shape[0]-rowOffset);
                    if(paletteSize>0) {
                        for(int i=0;i<take;i++) {
                            long at=rowOffset+i;
                            if(at==0) continue;
                            int x=(int)at-1, raw=u(buffer,cursor+i);
                            int left=x>0?u(current,x-1):0, up=u(previous,x), diagonal=x>0?u(previous,x-1):0;
                            int predictor=filter==0?0:filter==1?left:filter==2?up:filter==3?(left+up)/2:paeth(left,up,diagonal);
                            current[x]=(byte)(raw+predictor);
                        }
                    }
                    rowOffset+=take; cursor+=take;
                    if(rowOffset==shape[0]) {
                        if(paletteSize>0) {
                            int mask=(1<<bpp)-1;
                            for(int x=0;x<shape[2];x++) {
                                int bit=x*bpp;
                                need(((u(current,bit/8)>>(8-bpp-bit%8))&mask)<paletteSize, "PNG 像素引用了不存在的调色板颜色。");
                            }
                            byte[] swap=previous;previous=current;current=swap;
                        }
                        rowOffset=0; row++;
                        if(row==shape[1]) {
                            row=0;pass++;
                            if(paletteSize>0 && pass<passes.size()) {
                                previous=new byte[(int)passes.get(pass)[0]-1];current=new byte[previous.length];
                            }
                        }
                    }
                }
                total+=count;
            }
            need(total==expected && pass==passes.size(), "PNG 只包含部分像素。");
            need(inflater.getRemaining()==0, "PNG 压缩流后存在额外数据。");
            while(span<spans.size()) need(spans.get(span++)[1]==0, "PNG 包含额外压缩流。");
        } catch(DataFormatException e) { throw new IOException("PNG 压缩校验失败。",e); }
        finally { inflater.end(); }
    }
    private static byte[] inflateBounded(byte[] a,int start,int count,Runnable check) throws IOException {
        Inflater z=new Inflater(); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192];
        z.setInput(a,start,count);
        try {
            while(!z.finished()) {
                check.run(); int n=z.inflate(buf);
                need(n>0 || z.finished(), "颜色配置压缩流损坏。");
                need(out.size()+n<=MAX_ICC_BYTES, "颜色配置过大。"); out.write(buf,0,n);
            }
            need(z.getRemaining()==0, "颜色配置含额外数据。"); return out.toByteArray();
        } catch(DataFormatException e) {throw new IOException("颜色配置损坏。",e);} finally {z.end();}
    }
    private static void validateIcc(byte[] profile) throws IOException {
        need(profile.length>=128 && be32(profile,0)==profile.length && ascii(profile,36,"acsp"), "ICC 颜色配置无效。");
    }

    /** Only the main image IFD orientation is consulted; thumbnails / GPS are never retained. */
    private static int exif(byte[] a,int base,int n) throws IOException {
        need(n>=8, "EXIF 数据不完整。");
        boolean le=u(a,base)==73 && u(a,base+1)==73;
        need(le || (u(a,base)==77 && u(a,base+1)==77), "EXIF 字节序无效。");
        need(t16(a,base+2,le)==42, "EXIF 头无效。");
        long offset=t32(a,base+4,le);
        need(offset>=8 && offset<=n-2L, "EXIF 图像目录无效。");
        int dir=base+(int)offset, entries=t16(a,dir,le);
        need((long)(dir-base)+2L+12L*entries+4L<=n, "EXIF 图像目录被截断。");
        int result=1; boolean seen=false;
        for(int i=0;i<entries;i++) {
            int e=dir+2+12*i;
            if(t16(a,e,le)==0x112) {
                need(!seen && t16(a,e+2,le)==3 && t32(a,e+4,le)==1, "EXIF 方向字段无效。");
                result=t16(a,e+8,le); need(result>=1&&result<=8, "EXIF 方向超出范围。"); seen=true;
            }
        }
        return result;
    }

    private static final class Jpeg {
        final byte[] a; final Runnable check; final ByteArrayOutputStream clean;
        final Huffman[][] tables=new Huffman[2][4]; final boolean[] quant=new boolean[4];
        final List<Component> components=new ArrayList<>();
        final byte[][] icc=new byte[255][];
        int p=2,w,h,maxH,maxV,mcuCols,mcuRows,orientation=1,restartInterval,scans,iccCount,iccBytes;
        boolean frame,progressive,exif,ended; long eobRun;
        Jpeg(byte[] a,Runnable check) {this.a=a;this.check=check;clean=new ByteArrayOutputStream(a.length);clean.write(255);clean.write(216);}
        Result parse() throws IOException {
            while(p<a.length) {
                check.run(); int begin=p; need(u(a,p++)==255,"JPEG 标记无效。");
                while(p<a.length && u(a,p)==255) p++;
                need(p<a.length,"JPEG 标记被截断。"); int marker=u(a,p++);
                need(marker!=0 && marker!=216 && !(marker>=208&&marker<=215),"JPEG 标记顺序无效。");
                if(marker==217) {
                    need(frame && scans>0 && p==a.length,"JPEG 不完整或包含额外图像。");
                    for(Component c:components) for(int level:c.levels) need(level>=0,"JPEG 缺少完整的图像扫描。");
                    clean.write(255);clean.write(217);ended=true;break;
                }
                need(p+2<=a.length,"JPEG 段被截断。"); int len=be16(a,p);
                need(len>=2 && len<=a.length-p,"JPEG 段长度无效。"); int d=p+2,end=p+len;
                boolean retain=true;
                switch(marker) {
                    case 192: case 194: parseFrame(d,end,marker==194);break;
                    case 196: parseHuffman(d,end);break;
                    case 219: parseQuant(d,end);break;
                    case 221: need(len==4,"JPEG 重启间隔无效。");restartInterval=be16(a,d);break;
                    case 218:
                        need(frame,"JPEG 扫描缺少图像头。");
                        clean.write(a,begin,end-begin); p=end; int entropyStart=p;
                        parseScan(d,end); clean.write(a,entropyStart,p-entropyStart); scans++;
                        need(scans<=256,"JPEG 扫描数量过多。"); continue;
                    case 224:
                        retain=false;
                        if(asciiWithin(a,d,end,"JFIF\0")) {
                            need(end-d>=14 && u(a,d+5)==1 && u(a,d+7)<=2 &&
                                end-d==14+3*u(a,d+12)*u(a,d+13), "JPEG JFIF 头无效。");
                            // Preserve the YCbCr declaration, but remove the private embedded thumbnail.
                            clean.write(255);clean.write(224);clean.write(0);clean.write(16);
                            clean.write(a,d,12);clean.write(0);clean.write(0);
                        }
                        break;
                    case 225:
                        retain=false;
                        if(ascii(a,d,"Exif\0\0") && end-d>=6) {
                            need(!exif,"JPEG 包含重复 EXIF。"); orientation=exif(a,d+6,end-d-6);exif=true;
                        }
                        break;
                    case 226:
                        need(!asciiWithin(a,d,end,"MPF\0"),"暂不支持 MPO 或多图 JPEG。"); retain=false;
                        if(asciiWithin(a,d,end,"ICC_PROFILE\0")) {
                            need(end-d>=14,"JPEG ICC 分片无效。");
                            int seq=u(a,d+12),count=u(a,d+13);
                            need(count>0 && seq>0 && seq<=count && (iccCount==0||count==iccCount) && icc[seq-1]==null,"JPEG ICC 分片重复或缺失。");
                            iccCount=count;iccBytes+=end-d-14;need(iccBytes<=MAX_ICC_BYTES,"JPEG 颜色配置过大。");
                            icc[seq-1]=Arrays.copyOfRange(a,d+14,end);retain=true;
                        }
                        break;
                    case 238:
                        // Adobe's tiny transform descriptor is needed for correct RGB interpretation.
                        retain=asciiWithin(a,d,end,"Adobe");
                        if(retain) need(end-d==12 && u(a,d+11)<=1,"不支持此 JPEG 颜色变换。");
                        break;
                    case 254: retain=false;break;
                    default:
                        if(marker>=224 && marker<=239) retain=false;
                        else throw bad("仅支持 8 位 Huffman 基线或渐进 JPEG 截图。");
                }
                if(retain) clean.write(a,begin,end-begin);
                p=end;
            }
            need(ended,"JPEG 文件缺少完整结尾。");
            if(iccCount>0) {
                ByteArrayOutputStream profile=new ByteArrayOutputStream(iccBytes);
                for(int i=0;i<iccCount;i++){need(icc[i]!=null,"JPEG ICC 分片缺失。");profile.write(icc[i],0,icc[i].length);}
                validateIcc(profile.toByteArray());
            }
            check.run();return new Result("image/jpeg",w,h,orientation,clean.toByteArray());
        }
        void parseFrame(int d,int end,boolean progressive) throws IOException {
            need(!frame && end-d>=6 && u(a,d)==8,"JPEG 图像头或位深不受支持。");
            h=be16(a,d+1);w=be16(a,d+3);dimensions(w,h);int count=u(a,d+5);
            need((count==1||count==3) && end-d==6+3*count,"仅支持灰度或 RGB JPEG。");
            int sum=0;
            for(int i=0;i<count;i++) {
                int c=d+6+3*i,id=u(a,c),sampling=u(a,c+1),hs=sampling>>4,vs=sampling&15,q=u(a,c+2);
                need(hs>=1&&hs<=4&&vs>=1&&vs<=4&&q<4,"JPEG 采样参数无效。");
                for(Component old:components) need(old.id!=id,"JPEG 颜色分量重复。");
                components.add(new Component(id,hs,vs,q));maxH=Math.max(maxH,hs);maxV=Math.max(maxV,vs);sum+=hs*vs;
            }
            need(sum<=10,"JPEG 采样块过多。");
            mcuCols=(w+8*maxH-1)/(8*maxH);mcuRows=(h+8*maxV-1)/(8*maxV);
            long totalBlocks=0;
            for(Component c:components) {
                c.stride=mcuCols*c.hs;c.actualCols=(w*c.hs+8*maxH-1)/(8*maxH);c.actualRows=(h*c.vs+8*maxV-1)/(8*maxV);
                long blocks=(long)c.stride*mcuRows*c.vs;totalBlocks+=blocks;
                // The 12 MP raster bounds memory; the independent block limit covers extreme shapes.
                need(totalBlocks<=2_000_000L,"JPEG 解码工作区过大。");
                if(progressive) c.nonzero=new long[(int)blocks];
            }
            this.progressive=progressive;frame=true;
        }
        void parseQuant(int d,int end) throws IOException {
            while(d<end) {
                int spec=u(a,d++),precision=spec>>4,id=spec&15;
                need(precision<=1&&id<4&&end-d>=64*(precision+1),"JPEG 量化表无效。");
                for(int i=0;i<64;i++){int q=precision==0?u(a,d++):be16(a,d);if(precision==1)d+=2;need(q>0,"JPEG 量化表含零。");} quant[id]=true;
            }
        }
        void parseHuffman(int d,int end) throws IOException {
            while(d<end) {
                need(end-d>=17,"JPEG Huffman 表被截断。");int spec=u(a,d++),kind=spec>>4,id=spec&15;
                need(kind<=1&&id<4,"JPEG Huffman 表编号无效。");int[] counts=new int[16];int total=0;
                for(int i=0;i<16;i++){counts[i]=u(a,d++);total+=counts[i];}
                need(total>0&&total<=256&&total<=end-d,"JPEG Huffman 表长度无效。");
                int[] symbols=new int[total];for(int i=0;i<total;i++) symbols[i]=u(a,d++);
                tables[kind][id]=new Huffman(counts,symbols);
            }
        }
        void parseScan(int d,int end) throws IOException {
            need(end-d>=4,"JPEG 扫描头不完整。");int count=u(a,d++);
            need(count>=1&&count<=components.size()&&end-d==2*count+3,"JPEG 扫描分量无效。");
            Component[] selected=new Component[count];int[] dc=new int[count],ac=new int[count];
            for(int i=0;i<count;i++) {
                int id=u(a,d++),sel=u(a,d++);for(Component c:components) if(c.id==id)selected[i]=c;
                need(selected[i]!=null&& (sel>>4)<4&&(sel&15)<4,"JPEG 扫描表无效。");
                for(int j=0;j<i;j++)need(selected[j]!=selected[i],"JPEG 扫描分量重复。");
                dc[i]=sel>>4;ac[i]=sel&15;need(quant[selected[i].q],"JPEG 缺少量化表。");
            }
            int ss=u(a,d++),se=u(a,d++),approx=u(a,d),ah=approx>>4,al=approx&15;
            if(progressive) need(ss<=se&&se<=63&&ah<=13&&al<=13&&(ss==0?se==0:count==1)&&(ah==0||ah==al+1),"JPEG 渐进扫描参数无效。");
            else need(ss==0&&se==63&&ah==0&&al==0,"JPEG 基线扫描参数无效。");
            for(int i=0;i<count;i++) {
                Component c=selected[i];for(int k=ss;k<=se;k++)need(ah==0?c.levels[k]==-1:c.levels[k]==ah,"JPEG 扫描顺序无效。");
                if(ss==0&&ah==0)need(tables[0][dc[i]]!=null,"JPEG 缺少 DC Huffman 表。");
                if(se>0)need(tables[1][ac[i]]!=null,"JPEG 缺少 AC Huffman 表。");
            }
            Bits bits=new Bits(a,p);long mcus=count==1?(long)selected[0].actualCols*selected[0].actualRows:(long)mcuCols*mcuRows;
            eobRun=0;int restart=0;
            for(long mcu=0;mcu<mcus;mcu++) {
                if((mcu&1023)==0)check.run();
                if(mcu>0 && restartInterval>0 && mcu%restartInterval==0) {
                    need(eobRun==0,"JPEG EOB 越过重启边界。");bits.restart(208+(restart++&7));
                }
                for(int ci=0;ci<count;ci++) {
                    Component c=selected[ci];int blocks=count==1?1:c.hs*c.vs;
                    for(int block=0;block<blocks;block++) {
                        int index=count==1 ? (int)(mcu/c.actualCols)*c.stride+(int)(mcu%c.actualCols) :
                            ((int)(mcu/mcuCols)*c.vs+block/c.hs)*c.stride+(int)(mcu%mcuCols)*c.hs+block%c.hs;
                        decodeBlock(bits,c,index,tables[0][dc[ci]],tables[1][ac[ci]],ss,se,ah,al);
                    }
                }
            }
            need(eobRun==0,"JPEG EOB 越过图像边界。");bits.finish();p=bits.p;
            for(Component c:selected)for(int k=ss;k<=se;k++)c.levels[k]=al;
        }
        void decodeBlock(Bits b,Component c,int index,Huffman dc,Huffman ac,int ss,int se,int ah,int al) throws IOException {
            if(ss==0) {
                if(ah==0) {int size=dc.read(b);need(size<=11,"JPEG DC 系数无效。");b.read(size);}
                else b.read(1);
                if(se==0)return;
            }
            int k=Math.max(1,ss);long nz=progressive?c.nonzero[index]:0;
            if(ah==0) {
                if(eobRun>0) {eobRun--;return;}
                while(k<=se) {
                    int symbol=ac.read(b),r=symbol>>4,size=symbol&15;
                    if(size!=0) {
                        need(size<=10,"JPEG AC 系数无效。");k+=r;need(k<=se,"JPEG AC 游程超出图像块。");b.read(size);nz|=1L<<k;k++;
                    } else if(r==15) {k+=16;need(k<=se+1,"JPEG 零游程超出图像块。");}
                    else {
                        need(progressive||r==0,"JPEG 基线 EOB 无效。");eobRun=(1L<<r)+b.read(r)-1;break;
                    }
                }
            } else {
                if(eobRun==0) {
                    while(k<=se) {
                        int symbol=ac.read(b),r=symbol>>4,size=symbol&15;
                        need(size<=1,"JPEG 细化系数无效。");
                        if(size==0&&r!=15) {eobRun=(1L<<r)+b.read(r);break;}
                        if(size==1)b.read(1);
                        int zeros=size==0?16:r;
                        while(k<=se) {
                            if((nz&(1L<<k))!=0)b.read(1);
                            else {if(zeros==0)break;zeros--;if(size==0&&zeros==0){k++;break;}}
                            k++;
                        }
                        need(zeros==0,"JPEG 细化游程超出图像块。");
                        if(size==1){need(k<=se,"JPEG 新系数超出图像块。");nz|=1L<<k;k++;}
                    }
                }
                if(eobRun>0){for(;k<=se;k++)if((nz&(1L<<k))!=0)b.read(1);eobRun--;}
            }
            if(progressive)c.nonzero[index]=nz;
        }
    }
    private static final class Component {
        final int id,hs,vs,q;final int[] levels=new int[64];int stride,actualCols,actualRows;long[] nonzero;
        Component(int id,int hs,int vs,int q){this.id=id;this.hs=hs;this.vs=vs;this.q=q;Arrays.fill(levels,-1);}
    }
    private static final class Huffman {
        final int[] min=new int[17],max=new int[17],base=new int[17],symbols;
        Huffman(int[] counts,int[] symbols) throws IOException {
            this.symbols=symbols;int code=0,index=0;
            for(int size=1;size<=16;size++) {
                int count=counts[size-1];min[size]=code;max[size]=code+count-1;base[size]=index;
                need(code+count<(1<<size),"JPEG Huffman 表溢出或使用保留码。");
                code=(code+count)<<1;index+=count;
            }
        }
        int read(Bits b) throws IOException {
            int code=0;
            for(int size=1;size<=16;size++){code=(code<<1)|b.read(1);if(code>=min[size]&&code<=max[size])return symbols[base[size]+code-min[size]];}
            throw bad("JPEG Huffman 编码损坏。");
        }
    }
    private static final class Bits {
        final byte[] a;int p,value,left;
        Bits(byte[] a,int p){this.a=a;this.p=p;}
        int read(int n) throws IOException {
            int result=0;
            for(int i=0;i<n;i++) {
                if(left==0) {
                    need(p<a.length,"JPEG 像素数据被截断。");value=u(a,p++);
                    if(value==255){need(p<a.length&&u(a,p++)==0,"JPEG 像素数据提前结束。");}left=8;
                }
                result=(result<<1)|((value>>(--left))&1);
            }
            return result;
        }
        void align() throws IOException {need(left==0||(value&((1<<left)-1))==((1<<left)-1),"JPEG 填充位损坏。");left=0;}
        void finish() throws IOException {align();need(p<a.length&&u(a,p)==255,"JPEG 扫描含额外像素数据。");}
        void restart(int expected) throws IOException {
            align();need(p<a.length&&u(a,p++)==255,"JPEG 缺少重启标记。");while(p<a.length&&u(a,p)==255)p++;
            need(p<a.length&&u(a,p++)==expected,"JPEG 重启标记顺序无效。");
        }
    }
    public static void requireMemoryHeadroom(long additionalBytes) throws IOException {
        Runtime runtime=Runtime.getRuntime();
        long available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());
        need(additionalBytes>=0 && available>=additionalBytes+12L*1024*1024,
            "当前可用内存不足以安全处理此截图，请关闭其他画面后重试。");
    }
    private static int paeth(int a,int b,int c) {
        int p=a+b-c, pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);
        return pa<=pb&&pa<=pc?a:pb<=pc?b:c;
    }
    private static int positiveInt(byte[] a,int p) throws IOException {long n=be32(a,p);need(n>0&&n<=Integer.MAX_VALUE,"图片尺寸无效。");return(int)n;}
    private static int u(byte[] a,int p){return a[p]&255;}
    private static int be16(byte[] a,int p){return(u(a,p)<<8)|u(a,p+1);}
    private static long be32(byte[] a,int p){return((long)be16(a,p)<<16)|be16(a,p+2);}
    private static int t16(byte[] a,int p,boolean le){return le?u(a,p)|(u(a,p+1)<<8):be16(a,p);}
    private static long t32(byte[] a,int p,boolean le){return le?((long)t16(a,p+2,true)<<16)|t16(a,p,true):be32(a,p);}
    private static boolean ascii(byte[] a,int p,String value){return asciiWithin(a,p,a.length,value);}
    private static boolean asciiWithin(byte[] a,int p,int end,String value){if(end-p<value.length())return false;for(int i=0;i<value.length();i++)if(u(a,p+i)!=value.charAt(i))return false;return true;}
    private static IOException bad(String message){return new IOException(message);}
    private static void need(boolean ok,String message) throws IOException {if(!ok)throw bad(message);}
}
