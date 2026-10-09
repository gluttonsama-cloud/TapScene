package com.tapscene.packageformat;

import static com.tapscene.packageformat.StrictJson.require;
import static com.tapscene.packageformat.ViewerPackageCodec.check;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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
    private static int big32(byte[] b,int at) { return (b[at]&255)<<24 | (b[at+1]&255)<<16 | (b[at+2]&255)<<8 | (b[at+3]&255); }
}
