package com.tapscene.data;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Only the round-trip fixture's four Os calls use real host descriptors and fsync.
 * Stock ShadowLinux cannot open directories or fstat descriptors. This Linux/JDK bridge
 * does not model Android filesystem durability or power loss, and never reports fake success. */
@Implements(Os.class)
public final class HostFileSyncShadow {
    private static Path root;
    private static final Map<FileDescriptor, FileChannel> open = new IdentityHashMap<>();
    private static final Set<FileDescriptor> closed = Collections.newSetFromMap(new IdentityHashMap<>());
    private static int directorySyncs, fileSyncs;

    public static synchronized void begin(File privateRoot) throws IOException {
        if (root != null || !open.isEmpty()) throw new IllegalStateException("Host FD scope already active");
        if (!System.getProperty("os.name").equals("Linux") || !Files.isDirectory(Path.of("/proc/self/fd")))
            throw new IllegalStateException("Host FD check requires Linux /proc");
        root = privateRoot.toPath().toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalStateException("Fixture root is not a directory");
        closed.clear();
        directorySyncs = fileSyncs = 0;
    }

    public static synchronized void beginProductionEvidence() {
        if (!open.isEmpty()) throw new IllegalStateException("Probe leaked descriptors");
        directorySyncs = fileSyncs = 0;
    }

    public static synchronized void assertProductionEvidence() {
        if (root == null || !open.isEmpty() || directorySyncs == 0 || fileSyncs == 0)
            throw new IllegalStateException("Missing actual directory/file fsync or leaked descriptors");
        System.out.println("HOST_FILE_SYNC backend=Linux/JDK actualDirectorySyncs=" + directorySyncs
                + " actualFileSyncs=" + fileSyncs + " openDescriptors=0");
    }

    public static synchronized void reset() throws IOException {
        boolean leaked = !open.isEmpty();
        IOException failure = null;
        for (FileChannel channel : open.values()) {
            try { channel.close(); } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        open.clear(); closed.clear(); root = null;
        if (failure != null) throw failure;
        if (leaked) throw new IllegalStateException("Closed leaked host descriptors after failed test");
    }

    @Implementation protected static synchronized FileDescriptor open(String name, int flags, int mode) throws ErrnoException {
        Path path = Path.of(name).toAbsolutePath().normalize();
        if (root == null || !path.startsWith(root)) return Shadow.directlyOn(Os.class, "open",
                ClassParameter.from(String.class, name), ClassParameter.from(int.class, flags), ClassParameter.from(int.class, mode));
        if (flags != OsConstants.O_RDONLY || mode != 0) throw new ErrnoException("open", OsConstants.EINVAL);
        FileChannel channel = null;
        try {
            if (!path.equals(path.toRealPath())) throw new IOException("Fixture path contains a symlink");
            channel = FileChannel.open(path, StandardOpenOption.READ);
            Field field = channel.getClass().getDeclaredField("fd");
            field.setAccessible(true);
            FileDescriptor descriptor = (FileDescriptor) field.get(channel);
            if (!descriptor.valid()) throw new IOException("Host channel has no valid descriptor");
            open.put(descriptor, channel);
            return descriptor;
        } catch (Exception e) {
            if (channel != null) try { channel.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw new ErrnoException("open", e instanceof NoSuchFileException ? OsConstants.ENOENT : OsConstants.EIO, e);
        }
    }

    @Implementation protected static synchronized StructStat fstat(FileDescriptor descriptor) throws ErrnoException {
        if (!owned(descriptor)) return Shadow.directlyOn(Os.class, "fstat", ClassParameter.from(FileDescriptor.class, descriptor));
        Map<String, Object> values = attributes(descriptor);
        // Only st_mode is consumed by these production calls; all available host fields are preserved.
        return new StructStat(number(values, "dev"), number(values, "ino"), (int) number(values, "mode"),
                number(values, "nlink"), (int) number(values, "uid"), (int) number(values, "gid"),
                number(values, "rdev"), number(values, "size"), seconds(values, "lastAccessTime"),
                seconds(values, "lastModifiedTime"), seconds(values, "ctime"), 0, 0);
    }

    @Implementation protected static synchronized void fsync(FileDescriptor descriptor) throws ErrnoException {
        if (!owned(descriptor)) {
            Shadow.directlyOn(Os.class, "fsync", ClassParameter.from(FileDescriptor.class, descriptor));
            return;
        }
        int mode = (int) number(attributes(descriptor), "mode");
        if (!OsConstants.S_ISDIR(mode) && !OsConstants.S_ISREG(mode)) throw new ErrnoException("fsync", OsConstants.EINVAL);
        try { descriptor.sync(); } catch (IOException e) { throw new ErrnoException("fsync", OsConstants.EIO, e); }
        if (OsConstants.S_ISDIR(mode)) directorySyncs++; else fileSyncs++;
    }

    @Implementation protected static synchronized void close(FileDescriptor descriptor) throws ErrnoException {
        if (!owned(descriptor)) {
            Shadow.directlyOn(Os.class, "close", ClassParameter.from(FileDescriptor.class, descriptor));
            return;
        }
        try { open.get(descriptor).close(); }
        catch (IOException e) { throw new ErrnoException("close", OsConstants.EIO, e); }
        open.remove(descriptor); closed.add(descriptor);
        if (descriptor.valid()) throw new ErrnoException("close", OsConstants.EIO);
    }

    private static boolean owned(FileDescriptor descriptor) throws ErrnoException {
        if (closed.contains(descriptor)) throw new ErrnoException("closed descriptor", OsConstants.EBADF);
        if (!open.containsKey(descriptor)) return false;
        if (!descriptor.valid() || !open.get(descriptor).isOpen()) throw new ErrnoException("invalid descriptor", OsConstants.EBADF);
        return true;
    }

    private static Map<String, Object> attributes(FileDescriptor descriptor) throws ErrnoException {
        try {
            Field field = FileDescriptor.class.getDeclaredField("fd");
            field.setAccessible(true);
            int number = field.getInt(descriptor);
            if (number < 0) throw new IOException("Invalid native file descriptor");
            // Follow the held FD, not its old pathname (which may have been renamed/deleted).
            return Files.readAttributes(Path.of("/proc/self/fd/" + number), "unix:*");
        } catch (Exception e) { throw new ErrnoException("fstat", OsConstants.EIO, e); }
    }
    private static long number(Map<String, Object> values, String key) { return ((Number) values.get(key)).longValue(); }
    private static long seconds(Map<String, Object> values, String key) { return ((FileTime) values.get(key)).to(TimeUnit.SECONDS); }
}
