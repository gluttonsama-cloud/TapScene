import com.tapscene.packageformat.ViewerPackageCodec;
import com.tapscene.packageformat.ViewerScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Real production Java reader/writer compared against the runtime-ts generated shared bytes. */
public final class CanonicalFixtureChecks {
    private CanonicalFixtureChecks() { }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        for (int schema = 1; schema <= 3; schema++) {
            String name = "schema-" + schema;
            byte[] expected = Files.readAllBytes(root.resolve(name + ".json"));
            ViewerScene scene = ViewerPackageCodec.parseScene(expected);
            if (scene.schemaVersion != schema || !Arrays.equals(expected, ViewerPackageCodec.writeScene(scene)) ||
                    !Files.readString(root.resolve(name + ".sha256")).equals(ViewerPackageCodec.contentDigest(scene)))
                throw new AssertionError("Cross-language canonical bytes/digest mismatch: " + name);
        }
        System.out.println("HOST_HOSTED_CANONICAL Java/TypeScript schemas=1,2,3 exact-bytes/SHA256/Unicode/fraction PASS");
    }
}
