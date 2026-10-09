package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;
import static com.tapscene.packageformat.ViewerPackageCodec.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Checks both ZIP views and byte boundaries; never trusts ZipInputStream's partial archive view. */
final class StrictZip {
    private static final long LOCAL = 0x04034b50L, CENTRAL = 0x02014b50L, END = 0x06054b50L, DESCRIPTOR = 0x08074b50L;
    private static final class Entry {
        String path; byte[] name; int flags, method, needed, time, date;
        long crc, compressed, size, offset, dataOffset;
    }
    static void extract(File zip, File destination, CancelCheck cancel) throws IOException {
        extract(zip, destination, cancel, false);
    }
    static void extract(File zip, File destination, CancelCheck cancel, boolean ai) throws IOException {
        require(Files.isRegularFile(zip.toPath(), LinkOption.NOFOLLOW_LINKS)
                && zip.length() >= 22 && zip.length() <= MAX_PACKAGE_BYTES, "ZIP missing or exceeds byte budget");
        try (RandomAccessFile input = new RandomAccessFile(zip, "r")) {
            List<Entry> entries = inspect(input, cancel, ai);
            long all = 0;
            for (Entry e : entries) {
                check(cancel); File output = resolvePackage(destination, e.path, ai);
                if (e.path.startsWith("assets/")) {
                    File assets = new File(destination, "assets");
                    if (!assets.exists()) require(assets.mkdir(), "Cannot create isolated assets directory");
                    require(Files.isDirectory(assets.toPath(), LinkOption.NOFOLLOW_LINKS), "Unsafe assets directory");
                }
                input.seek(e.dataOffset); CRC32 crc = new CRC32(); long actual;
                try (OutputStream out = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    actual = extractEntry(input, e, out, crc, cancel);
                }
                all += actual;
                require(actual == e.size && crc.getValue() == e.crc && all <= MAX_PACKAGE_BYTES, "ZIP actual length or CRC mismatch");
            }
        }
    }
    private static List<Entry> inspect(RandomAccessFile in, CancelCheck cancel, boolean ai) throws IOException {
        long fileLength = in.length(); in.seek(fileLength - 22);
        require(u32(in) == END, "ZIP must end with a single comment-free end record");
        require(u16(in) == 0 && u16(in) == 0, "Multi-disk ZIP is unsupported");
        int diskCount = u16(in), count = u16(in); long cdSize = u32(in), cdOffset = u32(in); int comment = u16(in);
        require(count >= 2 && count <= (ai ? MAX_ASSETS + 5 : MAX_FILES) && diskCount == count && comment == 0
                && cdOffset != 0xffffffffL && cdSize != 0xffffffffL && cdOffset + cdSize == fileLength - 22,
                "Invalid central directory or ZIP64 archive");
        List<Entry> entries = new ArrayList<>(); Set<String> paths = new HashSet<>(); long total = 0;
        in.seek(cdOffset);
        for (int i = 0; i < count; i++) {
            check(cancel); require(in.getFilePointer() + 46 <= fileLength - 22 && u32(in) == CENTRAL, "Truncated ZIP directory");
            int made = u16(in); Entry e = new Entry(); e.needed = u16(in); e.flags = u16(in); e.method = u16(in);
            e.time = u16(in); e.date = u16(in); e.crc = u32(in); e.compressed = u32(in); e.size = u32(in);
            int nameLength = u16(in), extra = u16(in), entryComment = u16(in), disk = u16(in), internal = u16(in);
            long attrs = u32(in); e.offset = u32(in);
            require(e.needed >= 10 && e.needed <= 20 && (e.flags & ~0x0808) == 0 && (e.method == 0 || e.method == 8), "Unsupported/encrypted ZIP flags or compression");
            require(extra == 0 && entryComment == 0 && disk == 0 && internal == 0, "ZIP metadata, extra fields, or multi-disk entry are unsupported");
            int unixType = (int)((attrs >>> 16) & 0xf000);
            require((attrs & 0x10) == 0 && (unixType == 0 || unixType == 0x8000)
                    && ((made >>> 8) == 0 || (made >>> 8) == 3), "ZIP directory, symlink, or special entry");
            require(nameLength >= 10 && nameLength <= 47 && in.getFilePointer() + nameLength <= fileLength - 22, "Invalid ZIP filename length");
            e.name = new byte[nameLength]; in.readFully(e.name);
            for (byte b : e.name) require(b >= 0x20 && b <= 0x7e, "Only ASCII package paths supported");
            e.path = new String(e.name, StandardCharsets.US_ASCII); if (ai) AiPackageCodec.safePath(e.path); else safePath(e.path); require(paths.add(e.path), "Duplicate ZIP entry");
            long entryLimit = e.path.equals("manifest.json") ? MAX_MANIFEST_BYTES : e.path.equals("scene.json") ? MAX_SCENE_BYTES : ai && !e.path.startsWith("assets/") ? RenderPlan.MAX_BYTES : MAX_PACKAGE_BYTES;
            require(e.size > 0 && e.size <= entryLimit && e.compressed > 0 && e.compressed <= MAX_PACKAGE_BYTES
                    && e.offset < cdOffset && e.offset != 0xffffffffL, "ZIP entry exceeds byte budget or uses ZIP64");
            require(e.method != 0 || e.compressed == e.size, "Stored ZIP size mismatch");
            require(e.size <= 1024L * 1024 || e.size <= 200L * e.compressed, "Abnormal ZIP expansion ratio");
            total += e.size; require(total <= MAX_PACKAGE_BYTES, "ZIP unpacked total exceeds budget"); entries.add(e);
        }
        require(in.getFilePointer() == cdOffset + cdSize && paths.contains("manifest.json") && paths.contains("scene.json"), "Incomplete or oversized ZIP directory");
        // Central entries can be ordered differently, but all local records must cover the archive exactly once.
        entries.sort((a,b) -> Long.compare(a.offset,b.offset)); long cursor = 0;
        for (int i = 0; i < entries.size(); i++) {
            check(cancel); Entry e = entries.get(i); require(e.offset == cursor && cursor + 30 <= cdOffset, "ZIP gaps, overlaps, or prefix payload");
            in.seek(cursor); require(u32(in) == LOCAL && u16(in) == e.needed && u16(in) == e.flags && u16(in) == e.method,
                    "ZIP local/central header mismatch");
            require(u16(in) == e.time && u16(in) == e.date, "ZIP timestamp header mismatch");
            long crc = u32(in), compressed = u32(in), size = u32(in); int nameLength = u16(in), extra = u16(in);
            require(nameLength == e.name.length && extra == 0, "ZIP local filename or extra mismatch");
            byte[] name = new byte[nameLength]; in.readFully(name); require(Arrays.equals(name, e.name), "ZIP filename views disagree");
            if ((e.flags & 8) == 0) require(crc == e.crc && compressed == e.compressed && size == e.size, "ZIP local sizes disagree");
            else require((crc == 0 && compressed == 0 && size == 0) || (crc == e.crc && compressed == e.compressed && size == e.size), "Invalid ZIP streaming header");
            e.dataOffset = in.getFilePointer(); long dataEnd = e.dataOffset + e.compressed;
            long next = i + 1 < entries.size() ? entries.get(i + 1).offset : cdOffset;
            require(dataEnd <= next && dataEnd <= cdOffset, "ZIP payload overlaps headers");
            in.seek(dataEnd);
            if ((e.flags & 8) != 0) {
                long descriptorSize = next - dataEnd; require(descriptorSize == 12 || descriptorSize == 16, "Invalid or ZIP64 data descriptor");
                if (descriptorSize == 16) require(u32(in) == DESCRIPTOR, "Missing ZIP data descriptor signature");
                require(u32(in) == e.crc && u32(in) == e.compressed && u32(in) == e.size, "ZIP data descriptor mismatch");
            } else require(dataEnd == next, "Unexpected ZIP payload or duplicate local entry");
            cursor = in.getFilePointer(); require(cursor == next, "ZIP entry boundary mismatch");
        }
        require(cursor == cdOffset, "ZIP data does not cover declared records"); return entries;
    }
    private static long extractEntry(RandomAccessFile input, Entry e, OutputStream out, CRC32 crc, CancelCheck cancel) throws IOException {
        byte[] compressed = new byte[16384], decoded = new byte[16384]; long remaining = e.compressed, total = 0;
        Inflater inflater = e.method == 8 ? new Inflater(true) : null;
        try {
            while (remaining > 0) {
                check(cancel); int n = (int)Math.min(remaining, compressed.length); input.readFully(compressed,0,n); remaining -= n;
                if (inflater == null) { total += n; require(total <= e.size, "ZIP inflated data exceeds declaration"); out.write(compressed,0,n); crc.update(compressed,0,n); }
                else {
                    require(!inflater.finished(), "Trailing compressed ZIP payload"); inflater.setInput(compressed,0,n);
                    while (!inflater.needsInput()) {
                        check(cancel); int size;
                        try { size = inflater.inflate(decoded); } catch (DataFormatException invalid) { throw new IllegalArgumentException("Damaged ZIP deflate stream", invalid); }
                        total += size; require(total <= e.size && total <= MAX_PACKAGE_BYTES, "ZIP inflated data exceeds declaration");
                        out.write(decoded,0,size); crc.update(decoded,0,size);
                        if (inflater.finished()) break;
                        require(size > 0 || inflater.needsInput(), "Invalid or dictionary ZIP stream");
                    }
                }
            }
            if (inflater != null) require(inflater.finished() && inflater.getBytesRead() == e.compressed, "Truncated or trailing ZIP deflate data");
            return total;
        } finally { if (inflater != null) inflater.end(); }
    }
    static void write(File output, File assetRoot, List<FileEntry> files, Map<String, byte[]> json, CancelCheck cancel) throws IOException {
        write(output, assetRoot, files, json, cancel, false);
    }
    static void write(File output, File assetRoot, List<FileEntry> files, Map<String, byte[]> json, CancelCheck cancel, boolean ai) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String name : json.keySet()) entries.add(entry(name, json.get(name).length));
        for (FileEntry f : files) if (!json.containsKey(f.path)) entries.add(entry(f.path, f.byteLength));
        require(entries.size() <= (ai ? MAX_ASSETS + 5 : MAX_FILES), "Too many ZIP files");
        long dataBytes = 0, centralBytes = 0;
        for (Entry e : entries) { dataBytes += 30 + e.name.length + e.size; centralBytes += 46 + e.name.length; }
        require(dataBytes + centralBytes + 22 <= MAX_PACKAGE_BYTES, "Export ZIP exceeds byte budget");
        boolean created = false;
        try {
            OutputStream raw = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); created = true;
            try (OutputStream out = raw) {
                long cursor = 0;
                for (Entry e : entries) {
                    check(cancel); byte[] bytes = json.get(e.path); CRC32 crc = new CRC32();
                    if (bytes != null) crc.update(bytes);
                    else {
                        long actual = 0;
                        try (InputStream in = new FileInputStream(resolve(assetRoot,e.path))) {
                            byte[] block=new byte[16384]; int n;
                            while((n=in.read(block))!=-1) {
                                check(cancel); actual += n; require(actual <= e.size, "Asset changed during export"); crc.update(block,0,n);
                            }
                        }
                        require(actual == e.size, "Asset changed during export");
                    }
                    e.crc = crc.getValue(); e.offset = cursor;
                    put32(out,LOCAL); put16(out,10); put16(out,0x800); put16(out,0); put16(out,0); put16(out,33);
                    put32(out,e.crc); put32(out,e.size); put32(out,e.size); put16(out,e.name.length); put16(out,0); out.write(e.name);
                    if (bytes != null) out.write(bytes);
                    else {
                        FileEntry declared = null; for (FileEntry f : files) if (f.path.equals(e.path)) declared = f;
                        require(declared != null, "Missing export asset declaration");
                        MessageDigest hash = newDigest(); CRC32 actualCrc = new CRC32(); long actual = 0;
                        try (InputStream in = new FileInputStream(resolve(assetRoot,e.path))) {
                            byte[] block=new byte[16384]; int n; while((n=in.read(block))!=-1) {
                                check(cancel); actual += n; require(actual <= e.size, "Asset changed during export");
                                out.write(block,0,n); hash.update(block,0,n); actualCrc.update(block,0,n);
                            }
                        }
                        require(actual == e.size && actualCrc.getValue() == e.crc && hex(hash.digest()).equals(declared.sha256), "Asset changed during export");
                    }
                    cursor += 30 + e.name.length + e.size;
                }
                long centralOffset = cursor;
                for (Entry e : entries) {
                    check(cancel); put32(out,CENTRAL); put16(out,10); put16(out,10); put16(out,0x800); put16(out,0);
                    put16(out,0); put16(out,33); put32(out,e.crc); put32(out,e.size); put32(out,e.size);
                    put16(out,e.name.length); put16(out,0); put16(out,0); put16(out,0); put16(out,0); put32(out,0); put32(out,e.offset); out.write(e.name);
                    cursor += 46 + e.name.length;
                }
                put32(out,END); put16(out,0); put16(out,0); put16(out,entries.size()); put16(out,entries.size());
                put32(out,cursor-centralOffset); put32(out,centralOffset); put16(out,0); check(cancel);
            }
            require(output.length() <= MAX_PACKAGE_BYTES, "Export exceeds ZIP budget");
        } catch (IOException | RuntimeException failure) {
            if (created) try { Files.deleteIfExists(output.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static File resolvePackage(File root, String path, boolean ai) throws IOException {
        if (!ai) return resolve(root, path);
        return AiPackageCodec.resolve(root, path);
    }
    private static Entry entry(String path, long length) { Entry e=new Entry(); e.path=path; e.name=path.getBytes(StandardCharsets.US_ASCII); e.size=length; e.compressed=length; return e; }
    private static int u16(RandomAccessFile in) throws IOException { return in.readUnsignedByte() | in.readUnsignedByte()<<8; }
    private static long u32(RandomAccessFile in) throws IOException { return (long)u16(in) | (long)u16(in)<<16; }
    private static void put16(OutputStream out, long n) throws IOException { out.write((int)n&255); out.write((int)(n>>>8)&255); }
    private static void put32(OutputStream out, long n) throws IOException { put16(out,n); put16(out,n>>>16); }
}
