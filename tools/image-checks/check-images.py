#!/usr/bin/env python3
"""Generate synthetic screenshots and attack cases, then run the production Java parser.
Requires Python Pillow and a JDK (the java launcher with jdk.compiler suffices).
No personal screenshot or downloaded test image is used. Android rendering is a separate test.
"""
from pathlib import Path
import argparse, datetime, io, json, os, shutil, struct, subprocess, tempfile, zlib
from PIL import Image, ImageCms

ROOT = Path(__file__).resolve().parents[2]
JAVA = shutil.which('java') or '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
OPTIONS = argparse.ArgumentParser()
OPTIONS.add_argument('--report', type=Path, help='Write a JSON report only after all checks pass')
ARGS = OPTIONS.parse_args()
MAGIC = b'\x89PNG\r\n\x1a\n'
def chunk(kind, payload):
    return struct.pack('>I',len(payload))+kind+payload+struct.pack('>I',zlib.crc32(kind+payload)&0xffffffff)
def png(w=7,h=5,raw=None,depth=8,color=6,interlace=0,extra=b''):
    if raw is None: raw=b''.join(b'\0'+bytes((x*31%256,y*53%256,19,255))*(w) for y in range(h) for x in [0])
    return MAGIC+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,depth,color,0,0,interlace))+extra+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
def tiff(orientation, little=True):
    order='<' if little else '>'
    return (b'II' if little else b'MM')+struct.pack(order+'HIH',42,8,1)+struct.pack(order+'HHIHHI',0x112,3,1,orientation,0,0)
def insert_jpeg(data, marker, payload):
    return data[:2]+bytes([255,marker])+struct.pack('>H',len(payload)+2)+payload+data[2:]
def jpeg(image, **kwargs):
    out=io.BytesIO();image.save(out,format='JPEG',quality=91,**kwargs);return out.getvalue()
def entropy_spans(data):
    spans=[];p=2
    while p<len(data):
        assert data[p]==255
        while data[p]==255:p+=1
        marker=data[p];p+=1
        if marker==217:break
        n=int.from_bytes(data[p:p+2],'big');p+=n
        if marker==218:
            begin=p
            while p<len(data)-1:
                if data[p]==255:
                    if data[p+1]==0 or 208<=data[p+1]<=215:p+=2;continue
                    break
                p+=1
            spans.append((begin,p))
    return spans

with tempfile.TemporaryDirectory(prefix='tapscene-images-') as tmp:
    tmp=Path(tmp);manifest=[]
    def add(name,data,expect=True,w=7,h=5,o=1):
        path=tmp/name;path.write_bytes(data)
        manifest.append('\t'.join(map(str,['accept' if expect else 'reject',path,w,h,o])))
    add('rgba.png',png())
    add('metadata.png',png(extra=chunk(b'tEXt',b'Note\0PRIVATE_ORIGINAL_SENTINEL')))
    add('private-gainmap.png',png(extra=chunk(b'gmAP',b'PRIVATE_ORIGINAL_SENTINEL')))
    add('private-exif-thumbnail.png',png(extra=chunk(b'eXIf',tiff(1)+b'PRIVATE_ORIGINAL_SENTINEL')))

    for orientation in range(1,9):
        add(f'orient-{orientation}.png',png(extra=chunk(b'eXIf',tiff(orientation,orientation%2==1))),o=orientation)
    add('palette.png',png(raw=b'\0\x01\x23\x01\x20'*5,depth=4,color=3,
        extra=chunk(b'PLTE',bytes([255,0,0,0,255,0,0,0,255,255,255,255]))))
    # All five PNG filters are reversed before checking palette indexes.
    palette=bytes([255,0,0,0,255,0,0,0,255,255,255,255])
    def paeth(a,b,c):
        p=a+b-c; distances=[abs(p-a),abs(p-b),abs(p-c)]
        return [a,b,c][distances.index(min(distances))]
    for mode in range(5):
        raw=b'';previous=bytes(7)
        for y in range(5):
            row=bytes((x+y)%4 for x in range(7));filtered=[]
            for x,value in enumerate(row):
                left=row[x-1] if x else 0;up=previous[x];diagonal=previous[x-1] if x else 0
                predictor=[0,left,up,(left+up)//2,paeth(left,up,diagonal)][mode]
                filtered.append((value-predictor)%256)
            raw+=bytes([mode])+bytes(filtered);previous=row
        add(f'palette-filter-{mode}.png',png(raw=raw,depth=8,color=3,extra=chunk(b'PLTE',palette)))
    # Actual Adam7 sample order, including degenerate pass shapes.
    for w,h in [(7,5),(1,1),(1,33),(33,1),(17,21)]:
        raw=b''
        for xs,ys,dx,dy in [(0,0,8,8),(4,0,8,8),(0,4,4,8),(2,0,4,4),(0,2,2,4),(1,0,2,2),(0,1,1,2)]:
            if w<=xs or h<=ys:continue
            for y in range(ys,h,dy):raw+=b'\0'+b''.join(bytes([x%256,y%256,34,255]) for x in range(xs,w,dx))
        add(f'adam7-{w}-{h}.png',png(w,h,raw,interlace=1),w=w,h=h)
    image=Image.new('RGB',(57,43));image.putdata([((x*7+y*3)%256,(x*11)%256,(y*17)%256) for y in range(43) for x in range(57)])
    for progressive in [False,True]:
        for subsampling in [0,1,2]:
            for restart in [0,3]:
                data=jpeg(image,progressive=progressive,subsampling=subsampling,restart_marker_blocks=restart)
                name=f'jpeg-{progressive}-{subsampling}-{restart}'
                add(name+'.jpg',data,w=57,h=43)
                for idx,(start,end) in enumerate(entropy_spans(data)):
                    if end-start>3:
                        add(f'{name}-partial-{idx}.jpg',data[:start]+data[start+(end-start)//2:],False)
        gray=jpeg(image.convert('L'),progressive=progressive)
        add(f'gray-{progressive}.jpg',gray,w=57,h=43)
    base=jpeg(image)
    for orientation in range(1,9):
        add(f'orient-{orientation}.jpg',insert_jpeg(base,225,b'Exif\0\0'+tiff(orientation,orientation%2==1)),w=57,h=43,o=orientation)
    # JFIF remains authoritative YCbCr even when component IDs happen to spell RGB.
    jfif_rgb=bytearray(base);p=2
    while p<len(jfif_rgb):
        marker=jfif_rgb[p+1];p+=2
        if marker==217:break
        n=int.from_bytes(jfif_rgb[p:p+2],'big');d=p+2
        if marker==192:
            for i,ident in enumerate(b'RGB'):jfif_rgb[d+6+3*i]=ident
        if marker==218:
            for i,ident in enumerate(b'RGB'):jfif_rgb[d+1+2*i]=ident
            break
        p+=n
    add('jfif-rgb-ids.jpg',bytes(jfif_rgb),w=57,h=43)
    add('private-exif-thumbnail.jpg',insert_jpeg(base,225,b'Exif\0\0'+tiff(1)+b'PRIVATE_ORIGINAL_SENTINEL'),w=57,h=43)
    thumbnail=b'PRIVATE_ORIGINAL_SENTINEL'.ljust(27,b'x')
    jfif=b'JFIF\0'+bytes([1,1,0,0,1,0,1,9,1])+thumbnail
    add('private-jfif-thumbnail.jpg',insert_jpeg(base,224,jfif),w=57,h=43)
    add('private-app.jpg',insert_jpeg(base,225,b'http://ns.adobe.com/xap/1.0/\0PRIVATE_ORIGINAL_SENTINEL'),w=57,h=43)
    icc=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    add('icc.jpg',jpeg(image,icc_profile=icc),w=57,h=43)
    add('icc.png',png(extra=chunk(b'iCCP',b'sRGB\0\0'+zlib.compress(icc))))
    for name,data in [
        ('mislabeled-jpeg.png',base),('mislabeled-png.jpg',png())]:add(name,data,w=57 if data==base else 7,h=43 if data==base else 5)
    rejects={
        'empty':b'', 'gif.png':b'GIF89a'+bytes(30), 'webp.jpg':b'RIFF'+bytes(4)+b'WEBP'+bytes(30),
        'oversize.png':bytes(10*1024*1024+1), 'crc.png':png()[:-8]+b'XXXXXXXX',
        'missing-iend.png':png()[:-12], 'trailing.png':png()+b'X', 'two-images.png':png()+png(),
        'apng.png':png(extra=chunk(b'acTL',struct.pack('>II',2,0))),
        'invalid-palette-index.png':png(raw=(b'\0'+bytes([7]*7))*5,depth=8,color=3,extra=chunk(b'PLTE',palette)),
        'extreme-axis.png':png(32769,1,b''),
        'over-pixel.png':png(4000,3001,b''), 'zero-width.png':png(0,5,b''),
        'short-raster.png':png(raw=b'\0'+bytes(27)), 'long-raster.png':png(raw=(b'\0'+bytes(28))*6),
        'filter-invalid.png':png(raw=(b'\x05'+bytes(28))*5),
        'invalid-orientation.png':png(extra=chunk(b'eXIf',tiff(9))),
        'icc-bomb.png':png(extra=chunk(b'iCCP',b'ICC\0\0'+zlib.compress(bytes(1024*1024+1)))),
        'truncated.jpg':base[:-2], 'trailing.jpg':base+b'X', 'two-images.jpg':base+base,
        'mpo.jpg':insert_jpeg(base,226,b'MPF\0'+bytes(16)),
        'cmyk.jpg':jpeg(image.convert('CMYK')), 'invalid-orientation.jpg':insert_jpeg(base,225,b'Exif\0\0'+tiff(0)),
    }
    # Correct container CRC must not conceal corrupt/truncated/full+extra zlib streams.
    for suffix,compressed in [('truncated',zlib.compress((b'\0'+bytes(28))*5)[:-1]),
        ('extra-stream',zlib.compress((b'\0'+bytes(28))*5)+zlib.compress(b'')),
        ('bad-adler',zlib.compress((b'\0'+bytes(28))*5)[:-1]+b'\xff')]:
        rejects[f'{suffix}.png']=MAGIC+chunk(b'IHDR',struct.pack('>IIBBBBB',7,5,8,6,0,0,0))+chunk(b'IDAT',compressed)+chunk(b'IEND',b'')
    broken_huffman=bytearray(base);marker=broken_huffman.index(b'\xff\xc4');broken_huffman[marker+5]=255
    rejects['corrupt-huffman.jpg']=bytes(broken_huffman)
    for name,data in rejects.items():add(name,data,False)
    manifest_path=tmp/'manifest.tsv';manifest_path.write_text('\n'.join(manifest))
    classes=tmp/'classes';classes.mkdir()
    subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:-options','-d',str(classes),
        str(ROOT/'android/app/src/main/java/com/tapscene/media/StrictImageInput.java')],check=True)
    subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-cp',str(classes),'-d',str(classes),str(ROOT/'tools/image-checks/StrictImageInputChecks.java')],check=True)
    subprocess.run([JAVA,'-Xmx128m','-cp',str(classes),'StrictImageInputChecks',str(manifest_path)],check=True)

    if ARGS.report:
        ARGS.report.parent.mkdir(parents=True,exist_ok=True)
        ARGS.report.write_text(json.dumps({
            'status':'PASS', 'checkedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'productionParser':'android/app/src/main/java/com/tapscene/media/StrictImageInput.java',
            'caseCount':len(manifest), 'cancellationCheck':'PASS', 'heapLimitMiB':128,
            'androidApi26Decode':'NOT_RUN', 'androidCurrentDecode':'NOT_RUN',
            'androidReason':'No Android SDK, adb device or emulator in this worker environment.',
            'cases':[{'name':Path(line.split('\t')[1]).name,'expected':line.split('\t')[0],'status':'PASS'} for line in manifest],
        },ensure_ascii=False,indent=2)+'\n')
        print('Host report:',ARGS.report)
