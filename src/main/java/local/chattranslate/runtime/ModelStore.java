package local.chattranslate.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Downloads, verifies and locates the default GGUF outside the versioned runtime directory. Call on a worker thread. */
public final class ModelStore {
   /** Pinned description of the default model; the build writes it into the bundled manifest. */
   public record ModelSpec(String fileName, URI url, String sha256, long size) {
      public ModelSpec {
         if (!fileName.matches("[a-zA-Z0-9._-]{1,120}\\.gguf") || !sha256.matches("[0-9a-f]{64}") || size <= 0
            || url.getScheme() == null || url.getHost() == null) {
            throw new IllegalArgumentException("Invalid model specification");
         }
      }
   }

   /** The model is absent and the user has not agreed to download it. */
   public static final class ModelMissingException extends IOException {
      public ModelMissingException() {
         super("翻訳モデルが未ダウンロードです。設定画面または /lct download から取得できます。");
      }
   }

   private static final int ATTEMPTS = 3;
   private static final long FREE_SPACE_MARGIN = 64L * 1024 * 1024;

   private final Path root;
   private final ModelSpec spec;
   private final HttpClient http;
   private final Duration stallTimeout;
   private final AtomicLong downloaded = new AtomicLong();

   public ModelStore(Path gameDir, ModelSpec spec, HttpClient http) {
      this(gameDir, spec, http, Duration.ofSeconds(60));
   }

   /** {@code stallTimeout} aborts an attempt that receives no bytes for that long. */
   public ModelStore(Path gameDir, ModelSpec spec, HttpClient http, Duration stallTimeout) {
      this.root = gameDir.resolve("localchattranslate/models");
      this.spec = spec;
      this.http = http;
      this.stallTimeout = stallTimeout;
   }

   public ModelSpec spec() { return spec; }
   public long downloadedBytes() { return downloaded.get(); }

   public Path modelPath() {
      return root.resolve(spec.sha256.substring(0, 16)).resolve(spec.fileName);
   }

   /** Cheap check for UI: the file has the expected size and was verified before. Never hashes. */
   public boolean isPresent() {
      try {
         Path model = modelPath();
         return Files.isRegularFile(model) && Files.size(model) == spec.size && Files.isRegularFile(marker());
      } catch (IOException e) {
         return false;
      }
   }

   /** Returns the verified model, hashing it only when its verification marker is stale. */
   public boolean isInstalled() throws IOException {
      Path model = modelPath();
      if (!Files.isRegularFile(model) || Files.size(model) != spec.size) return false;
      Path marker = marker();
      String expected = markerText(model);
      if (Files.isRegularFile(marker) && Files.readString(marker).equals(expected)) return true;
      if (!sha256(model).equals(spec.sha256)) return false;
      writeAtomically(marker, expected);
      return true;
   }

   /** Downloads the model if needed. {@code cancelled} is polled between chunks. */
   public Path ensure(boolean downloadAllowed, BooleanSupplier cancelled) throws IOException, InterruptedException {
      Files.createDirectories(root);
      try (FileChannel channel = FileChannel.open(root.resolve(".download.lock"),
         StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
         if (isInstalled()) return modelPath();
         if (!downloadAllowed) throw new ModelMissingException();
         Path model = modelPath();
         Files.createDirectories(model.getParent());
         Path part = model.resolveSibling(spec.fileName + ".part");
         IOException last = null;
         for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (cancelled.getAsBoolean()) throw new IOException("翻訳エンジンを停止しました。");
            try {
               fetch(part, cancelled);
               last = null;
               break;
            } catch (ChecksumException e) {
               Files.deleteIfExists(part);
               throw e;
            } catch (IOException e) {
               last = e;
               if (attempt + 1 < ATTEMPTS) Thread.sleep(2000L << attempt);
            }
         }
         if (last != null) throw last;
         Files.move(part, model, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         writeAtomically(marker(), markerText(model));
         return model;
      }
   }

   private static final class ChecksumException extends IOException {
      ChecksumException() {
         super("ダウンロードした翻訳モデルの検証に失敗しました。再試行してください。");
      }
   }

   private void fetch(Path part, BooleanSupplier cancelled) throws IOException, InterruptedException {
      long offset = Files.isRegularFile(part) ? Files.size(part) : 0;
      if (offset > spec.size) {
         Files.delete(part);
         offset = 0;
      }
      long usable = Files.getFileStore(part.getParent()).getUsableSpace();
      if (usable < spec.size - offset + FREE_SPACE_MARGIN) {
         throw new IOException("翻訳モデルを保存する空き容量が足りません（約 " + (spec.size - offset) / 1_000_000 + " MB 必要）。");
      }
      MessageDigest digest = sha256Digest();
      if (offset > 0) {
         try (InputStream existing = Files.newInputStream(part)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = existing.read(buffer)) != -1) digest.update(buffer, 0, read);
         }
      }
      if (offset < spec.size) {
         HttpRequest.Builder request = HttpRequest.newBuilder(spec.url).timeout(Duration.ofSeconds(30)).GET();
         if (offset > 0) request.header("Range", "bytes=" + offset + "-");
         HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
         InputStream body = response.body();
         boolean append = offset > 0 && response.statusCode() == 206
            && response.headers().firstValue("Content-Range").orElse("").startsWith("bytes " + offset + "-");
         if (!append) {
            if (response.statusCode() != 200) {
               body.close();
               throw new IOException("翻訳モデルを取得できませんでした（HTTP " + response.statusCode() + "）。");
            }
            offset = 0;
            digest.reset();
         }
         downloaded.set(offset);
         AtomicLong lastProgress = new AtomicLong(System.nanoTime());
         ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("localchattranslate-download-watchdog").factory());
         watchdog.scheduleAtFixedRate(() -> {
            if (cancelled.getAsBoolean()
               || System.nanoTime() - lastProgress.get() > stallTimeout.toNanos()) {
               try {
                  body.close();
               } catch (IOException ignored) {
                  // The reading thread reports the failure.
               }
            }
         }, 200, 200, TimeUnit.MILLISECONDS);
         try (InputStream input = body; OutputStream output = Files.newOutputStream(part,
            StandardOpenOption.CREATE, append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[1024 * 1024];
            long total = offset;
            int read;
            while ((read = input.read(buffer)) != -1) {
               if (cancelled.getAsBoolean()) throw new IOException("翻訳エンジンを停止しました。");
               total += read;
               if (total > spec.size) throw new ChecksumException();
               output.write(buffer, 0, read);
               digest.update(buffer, 0, read);
               downloaded.set(total);
               lastProgress.set(System.nanoTime());
            }
         } finally {
            watchdog.shutdownNow();
         }
      }
      if (Files.size(part) != spec.size) throw new IOException("翻訳モデルのダウンロードが途中で切れました。");
      if (!HexFormat.of().formatHex(digest.digest()).equals(spec.sha256)) throw new ChecksumException();
   }

   private Path marker() {
      return modelPath().resolveSibling(spec.fileName + ".verified");
   }

   private String markerText(Path model) throws IOException {
      return spec.sha256 + " " + spec.size + " " + Files.getLastModifiedTime(model).toMillis() + "\n";
   }

   private static void writeAtomically(Path target, String text) throws IOException {
      Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + "-", ".tmp");
      try {
         Files.writeString(temp, text);
         Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
         Files.deleteIfExists(temp);
      }
   }

   static String sha256(Path file) throws IOException {
      MessageDigest digest = sha256Digest();
      try (InputStream stream = new DigestInputStream(Files.newInputStream(file), digest)) {
         byte[] buffer = new byte[1024 * 1024];
         while (stream.read(buffer) != -1) { }
      }
      return HexFormat.of().formatHex(digest.digest());
   }

   private static MessageDigest sha256Digest() {
      try {
         return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }
}
