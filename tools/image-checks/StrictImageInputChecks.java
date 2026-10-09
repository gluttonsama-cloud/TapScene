import com.tapscene.media.StrictImageInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs the exact production parser with no Android classes or alternate decoding implementation. */
public final class StrictImageInputChecks {
    public static void main(String[] args) throws Exception {
        int passed=0;
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            String[] parts=line.split("\\t");byte[] bytes=Files.readAllBytes(Path.of(parts[1]));
            if(parts[0].equals("reject")) {
                boolean rejected=false;
                try {StrictImageInput.inspect(bytes);} catch(java.io.IOException expected){rejected=true;}
                if(!rejected)throw new AssertionError("Accepted invalid input: "+parts[1]);
            } else {
                StrictImageInput.Result result=StrictImageInput.inspect(bytes);
                if(result.width!=Integer.parseInt(parts[2])||result.height!=Integer.parseInt(parts[3])||result.orientation!=Integer.parseInt(parts[4]))throw new AssertionError("Metadata: "+parts[1]);
                if(new String(result.decodeBytes,java.nio.charset.StandardCharsets.ISO_8859_1).contains("PRIVATE_ORIGINAL_SENTINEL"))throw new AssertionError("Metadata leaked to decoder");
                if(parts[1].endsWith("jfif-rgb-ids.jpg")) {
                    java.awt.image.BufferedImage original=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                    java.awt.image.BufferedImage sanitized=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(result.decodeBytes));
                    for(int y=0;y<original.getHeight();y++)for(int x=0;x<original.getWidth();x++)
                        if(original.getRGB(x,y)!=sanitized.getRGB(x,y))throw new AssertionError("JFIF color declaration changed pixels");
                }
                StrictImageInput.Result clean=StrictImageInput.inspect(result.decodeBytes);
                if(clean.orientation!=1||clean.width!=result.width||clean.height!=result.height)throw new AssertionError("Sanitized decode metadata");
            }
            passed++;
        }
        boolean cancelled=false;
        try {StrictImageInput.inspect(new byte[]{1},()->{throw new java.util.concurrent.CancellationException();});}
        catch(java.util.concurrent.CancellationException expected){cancelled=true;}
        if(!cancelled)throw new AssertionError("Cancellation not propagated");
        System.out.println("PASS StrictImageInput: "+passed+" synthetic input checks; cancellation propagated");
    }
}
