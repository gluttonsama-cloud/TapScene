package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;
import static com.tapscene.packageformat.ViewerPackageCodec.check;

import java.io.File;
import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Structural PNG verification; Android separately decodes every actual pixel and checks opacity. */
final class SafePng {
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList("IHDR","IDAT","IEND","sRGB","gAMA","cHRM","sBIT"));
    static void validate(File file, int expectedWidth, int expectedHeight, ViewerPackageCodec.CancelCheck cancel) throws IOException {
        Inflater pixels = new Inflater();
        try (RandomAccessFile in = new RandomAccessFile(file,"r")) {
            require(in.length() >= 57 && in.length() <= ViewerPackageCodec.MAX_PACKAGE_BYTES, "PNG missing or exceeds budget");
            byte[] signature = new byte[8]; in.readFully(signature); require(Arrays.equals(signature,SIGNATURE), "Invalid PNG signature");
            byte[] block = new byte[16384], inflated = new byte[16384];
            boolean header = false, hasPixels = false, pixelsClosed = false, ended = false;
            long inflatedCount = 0, expectedInflated = 0; int rowBytes = 0, color = 0, chunks = 0;
            Set<String> metadata = new HashSet<>();
            while (!ended) {
                check(cancel); require(++chunks <= 4096 && in.length()-in.getFilePointer() >= 12, "PNG truncated or too many chunks");
                long length = in.readInt() & 0xffffffffL; byte[] typeBytes = new byte[4]; in.readFully(typeBytes);
                String type = new String(typeBytes,StandardCharsets.US_ASCII);
                require(ALLOWED.contains(type) && length <= in.length()-in.getFilePointer()-4, "Unsupported PNG metadata or chunk bounds");
                require(header || type.equals("IHDR"), "PNG must begin with IHDR");
                if (hasPixels && !type.equals("IDAT")) pixelsClosed = true;
                switch (type) {
                    case "IHDR": require(!header && length == 13, "Invalid PNG header"); break;
                    case "IDAT": require(header && !pixelsClosed, "Noncontiguous PNG pixel chunks"); hasPixels = true; break;
                    case "IEND": require(hasPixels && length == 0, "PNG has no pixels or invalid ending"); ended = true; break;
                    default:
                        require(!hasPixels && metadata.add(type), "Duplicate or late PNG metadata");
                        require(length == (type.equals("sRGB") ? 1 : type.equals("gAMA") ? 4 : type.equals("cHRM") ? 32 : color == 6 ? 4 : 3), "Invalid PNG metadata size");
                }
                CRC32 crc = new CRC32(); crc.update(typeBytes); long remaining = length;
                while (remaining > 0) {
                    check(cancel); int n=(int)Math.min(remaining,block.length); in.readFully(block,0,n); remaining-=n; crc.update(block,0,n);
                    if (type.equals("IHDR")) {
                        int width = big32(block,0), height = big32(block,4); color = block[9]&255;
                        require(width == expectedWidth && height == expectedHeight && (block[8]&255) == 8
                                && (color == 2 || color == 6) && block[10] == 0 && block[11] == 0 && block[12] == 0,
                                "PNG dimensions, pixel format, or interlace outside static profile");
                        rowBytes = width * (color == 2 ? 3 : 4) + 1; expectedInflated = (long)rowBytes * height; header = true;
                    } else if (type.equals("IDAT")) {
                        require(!pixels.finished(), "Trailing PNG pixel payload"); pixels.setInput(block,0,n);
                        while (!pixels.needsInput()) {
                            check(cancel); int count;
                            try { count=pixels.inflate(inflated); } catch (DataFormatException e) { throw new IllegalArgumentException("Invalid PNG pixel compression",e); }
                            require(inflatedCount+count <= expectedInflated, "PNG inflated pixel data exceeds dimensions");
                            for (int i=0;i<count;i++) if ((inflatedCount+i)%rowBytes == 0) require((inflated[i]&255) <= 4, "Invalid PNG scanline filter");
                            inflatedCount+=count;
                            if (pixels.finished()) break;
                            require(count > 0 || pixels.needsInput(), "PNG pixel stream stalled or requires a dictionary");
                        }
                        require(!pixels.finished() || pixels.getRemaining() == 0, "PNG has trailing compressed data");
                    } else if (type.equals("sRGB")) require((block[0]&255) <= 3, "Invalid PNG rendering intent");
                    else if (type.equals("gAMA")) require(big32(block,0) != 0, "Invalid PNG gamma");
                    else if (type.equals("sBIT")) for (int i=0;i<n;i++) require(block[i] >= 1 && block[i] <= 8, "Invalid PNG sample precision");
                }
                require(crc.getValue() == (in.readInt()&0xffffffffL), "PNG chunk CRC mismatch");
            }
            require(in.getFilePointer() == in.length() && pixels.finished() && inflatedCount == expectedInflated,
                    "PNG truncated pixels or trailing file content"); check(cancel);
        } finally { pixels.end(); }
    }
    /** Compare decoded source pixels, not compressed PNG bytes. At most four scanlines are held.
     * Structural/CRC/full-inflate validation happens first; only fixed RGB/RGBA 8-bit PNG is read. */
    static void validateCrop(File base, int width, int height, File crop, ViewerScene.PixelRect box,
            ViewerPackageCodec.CancelCheck cancel) throws IOException {
        validate(base, width, height, cancel); validate(crop, box.width, box.height, cancel);
        try (Rows source = new Rows(base, width, height, cancel); Rows layer = new Rows(crop, box.width, box.height, cancel)) {
            for (int y = 0; y < box.y + box.height; y++) {
                check(cancel); source.next();
                if (y < box.y) continue;
                layer.next();
                for (int x = 0; x < box.width; x++) {
                    int a = source.pixel(x + box.x), b = layer.pixel(x);
                    require((a >>> 24) == 255 && a == b, "Region PNG pixels differ from the declared safe-base crop");
                }
            }
        }
    }

    private static final class Rows implements Closeable {
        private final RandomAccessFile file;
        private final InflaterInputStream pixels;
        private final ViewerPackageCodec.CancelCheck cancel;
        private final int channels, height;
        private byte[] previous, current;
        private int row;
        Rows(File path, int expectedWidth, int expectedHeight, ViewerPackageCodec.CancelCheck cancel) throws IOException {
            this.cancel = cancel; file = new RandomAccessFile(path, "r");
            try {
                file.seek(8);
                require(file.readInt() == 13 && file.readInt() == 0x49484452, "PNG changed before crop comparison");
                int width = file.readInt(); height = file.readInt();
                require(width == expectedWidth && height == expectedHeight && file.readUnsignedByte() == 8,
                        "PNG dimensions changed before crop comparison");
                int color = file.readUnsignedByte(); require(color == 2 || color == 6, "Unsupported crop pixel format");
                channels = color == 2 ? 3 : 4;
                require(file.readUnsignedByte() == 0 && file.readUnsignedByte() == 0 && file.readUnsignedByte() == 0,
                        "Unsupported crop PNG encoding");
                file.readInt(); // IHDR CRC was independently verified above.
                previous = new byte[width * channels]; current = new byte[previous.length];
                pixels = new InflaterInputStream(new Idat(file, cancel));
            } catch (IOException | RuntimeException error) { file.close(); throw error; }
        }
        void next() throws IOException {
            check(cancel); require(row++ < height, "Crop comparison exceeded image rows");
            int filter = pixels.read(); require(filter >= 0 && filter <= 4, "Invalid crop PNG scanline");
            int count = 0;
            while (count < current.length) {
                check(cancel); int n = pixels.read(current, count, current.length - count);
                require(n > 0, "Truncated crop PNG pixels"); count += n;
            }
            for (int i = 0; i < current.length; i++) {
                int left = i >= channels ? current[i - channels] & 255 : 0;
                int up = previous[i] & 255, upperLeft = i >= channels ? previous[i - channels] & 255 : 0;
                int predict;
                switch (filter) {
                    case 0: predict = 0; break;
                    case 1: predict = left; break;
                    case 2: predict = up; break;
                    case 3: predict = (left + up) / 2; break;
                    case 4: predict = paeth(left, up, upperLeft); break;
                    default: throw new AssertionError("validated filter");
                }
                current[i] = (byte)((current[i] & 255) + predict);
            }
            byte[] swap = previous; previous = current; current = swap;
        }
        int pixel(int x) {
            int at = x * channels;
            return (channels == 4 ? (previous[at + 3] & 255) : 255) << 24
                    | (previous[at] & 255) << 16 | (previous[at + 1] & 255) << 8 | previous[at + 2] & 255;
        }
        @Override public void close() throws IOException { try { pixels.close(); } finally { file.close(); } }
    }
    /** A contiguous stream of IDAT payloads; metadata and CRC bytes never reach Inflater. */
    private static final class Idat extends InputStream {
        private final RandomAccessFile file;
        private final ViewerPackageCodec.CancelCheck cancel;
        private long remaining;
        private boolean chunk, ended;
        Idat(RandomAccessFile file, ViewerPackageCodec.CancelCheck cancel) { this.file = file; this.cancel = cancel; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (remaining == 0 && !ended) {
                check(cancel);
                if (chunk) { file.readInt(); chunk = false; }
                require(file.length() - file.getFilePointer() >= 12, "Truncated PNG while comparing crop");
                long size = file.readInt() & 0xffffffffL; int type = file.readInt();
                require(size <= file.length() - file.getFilePointer() - 4, "PNG changed while comparing crop");
                if (type == 0x49444154) { remaining = size; chunk = true; }
                else if (type == 0x49454e44) { ended = true; }
                else file.seek(file.getFilePointer() + size + 4);
            }
            if (ended) return -1;
            check(cancel); int n = (int)Math.min(length, remaining); file.readFully(bytes, offset, n); remaining -= n; return n;
        }
    }
    private static int paeth(int left, int up, int upperLeft) {
        int p = left + up - upperLeft, a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - upperLeft);
        return a <= b && a <= c ? left : b <= c ? up : upperLeft;
    }
    private static int big32(byte[] b,int at) { return (b[at]&255)<<24 | (b[at+1]&255)<<16 | (b[at+2]&255)<<8 | (b[at+3]&255); }
}
