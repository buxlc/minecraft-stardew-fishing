package cn.bucheng.stardewfishing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Writes a complete replacement beside the target before an atomic rename where supported. */
final class AtomicFiles {
    private AtomicFiles() { }
    static void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
