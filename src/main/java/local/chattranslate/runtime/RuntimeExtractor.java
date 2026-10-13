package local.chattranslate.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Streams the verified native runtime and GGUF out of the MOD JAR. Call on a worker thread. */
public final class RuntimeExtractor {
   private static final String MANIFEST_RESOURCE = "/bundled/manifest.json";
   private static final String BUNDLE_PREFIX = "bundled/";

   private RuntimeExtractor() {
   }

   public record ExtractedBundle(Path runtimeDir, Path modelPath, String version) {
   }

   private record Entry(String resource, String sha256, long size) {
   }

   public static ExtractedBundle extract(Path gameDir) throws IOException {
      JsonObject manifest;
      try (InputStream stream = resource(MANIFEST_RESOURCE)) {
         manifest = JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      } catch (RuntimeException e) {
         throw new IOException("Invalid bundled manifest", e);
      }
      String version;
      List<Entry> entries = new ArrayList<>();
      try {
         version = manifest.get("bundleVersion").getAsString();
         if (!version.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid version");
         JsonArray files = manifest.getAsJsonArray("files");
         Set<String> names = new HashSet<>();
         for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            Entry entry = new Entry(file.get("resource").getAsString(),
               file.get("sha256").getAsString(), file.get("size").getAsLong());
            if (!entry.resource.matches("bundled/(llama\\.cpp/windows-x64/[a-zA-Z0-9._-]+|model/translategemma-4b\\.gguf)")
               || !entry.sha256.matches("[0-9a-f]{64}") || entry.size <= 0 || !names.add(entry.resource)) {
               throw new IllegalArgumentException("Invalid manifest entry");
            }
            entries.add(entry);
         }
         if (entries.stream().noneMatch(e -> e.resource.endsWith("/llama-server.exe"))
            || entries.stream().noneMatch(e -> e.resource.endsWith("/translategemma-4b.gguf"))) {
            throw new IllegalArgumentException("Incomplete manifest");
         }
      } catch (RuntimeException e) {
         throw new IOException("Invalid bundled manifest", e);
      }

      Path root = gameDir.resolve("localchattranslate/runtime");
      Files.createDirectories(root);
      try (FileChannel channel = FileChannel.open(root.resolve(".extract.lock"),
         StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
         Path versionDir = root.resolve(version);
         Files.createDirectories(versionDir);
         Path complete = versionDir.resolve(".complete");
         boolean valid = Files.isRegularFile(complete) && Files.readString(complete).equals(version + "\n");
         for (Entry entry : entries) {
            Path output = versionDir.resolve(entry.resource.substring(BUNDLE_PREFIX.length()));
            Files.createDirectories(output.getParent());
            if (!verify(output, entry)) {
               valid = false;
               Files.deleteIfExists(complete);
               extractOne(output, entry);
            }
         }
         if (!valid) {
            Path marker = Files.createTempFile(versionDir, ".complete-", ".tmp");
            try {
               Files.writeString(marker, version + "\n");
               Files.move(marker, complete, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
               Files.deleteIfExists(marker);
            }
         }
         return new ExtractedBundle(versionDir.resolve("llama.cpp/windows-x64"),
            versionDir.resolve("model/translategemma-4b.gguf"), version);
      }
   }

   private static void extractOne(Path target, Entry entry) throws IOException {
      Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + "-", ".tmp");
      try {
         MessageDigest digest = sha256();
         long count = 0;
         try (InputStream stream = new DigestInputStream(resource("/" + entry.resource), digest);
              OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
               output.write(buffer, 0, read);
               count += read;
            }
         }
         if (count != entry.size || !hex(digest.digest()).equals(entry.sha256)) {
            throw new IOException("Bundled resource checksum mismatch: " + entry.resource);
         }
         Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
         Files.deleteIfExists(temp);
      }
   }

   private static boolean verify(Path file, Entry entry) throws IOException {
      if (!Files.isRegularFile(file) || Files.size(file) != entry.size) return false;
      MessageDigest digest = sha256();
      try (InputStream stream = new DigestInputStream(Files.newInputStream(file), digest)) {
         byte[] buffer = new byte[1024 * 1024];
         while (stream.read(buffer) != -1) { }
      }
      return hex(digest.digest()).equals(entry.sha256);
   }

   private static InputStream resource(String name) throws IOException {
      InputStream stream = RuntimeExtractor.class.getResourceAsStream(name);
      if (stream == null) throw new IOException("Missing bundled resource: " + name);
      return stream;
   }

   private static MessageDigest sha256() {
      try {
         return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }

   private static String hex(byte[] bytes) {
      return java.util.HexFormat.of().formatHex(bytes);
   }
}
