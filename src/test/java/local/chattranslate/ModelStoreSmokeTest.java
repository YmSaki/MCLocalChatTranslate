package local.chattranslate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import local.chattranslate.runtime.ModelStore;

/** Exercises ModelStore against a loopback HTTP server; this class is never included in the MOD. */
public final class ModelStoreSmokeTest {
   private static final int SIZE = 3 * 1024 * 1024 + 12345;
   private static byte[] blob;
   private static final List<String> requests = new CopyOnWriteArrayList<>();
   /** What the /blob handler does for the next request. */
   private enum Mode { NORMAL, IGNORE_RANGE, CORRUPT, TRUNCATE_ONCE, NOT_FOUND, STALL_ONCE }
   private static volatile Mode mode = Mode.NORMAL;
   private static final AtomicBoolean firstDone = new AtomicBoolean();

   public static void main(String[] args) throws Exception {
      blob = new byte[SIZE];
      new Random(42).nextBytes(blob);
      String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(blob));
      Path base = Path.of("").toAbsolutePath();
      deleteTree(base.resolve("cases"));

      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/redirect", exchange -> {
         requests.add("redirect");
         exchange.getResponseHeaders().add("Location", "/blob");
         exchange.sendResponseHeaders(302, -1);
         exchange.close();
      });
      server.createContext("/blob", ModelStoreSmokeTest::serveBlob);
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
      server.start();
      URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
      ModelStore.ModelSpec spec = new ModelStore.ModelSpec("test-model.gguf", url, sha, SIZE);
      HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
      try {
         noConsentMakesNoRequest(base, spec, http);
         downloadsThroughRedirectAndCaches(base, spec, http);
         resumesPartialDownload(base, spec, http);
         restartsWhenServerIgnoresRange(base, spec, http);
         rejectsChecksumMismatch(base, spec, http);
         retriesAfterTruncatedResponse(base, spec, http);
         retriesAfterStall(base, spec, http);
         failsOnHttpError(base, spec, http);
         rejectsCorruptedInstalledModel(base, spec, http);
         stopsWhenCancelled(base, spec, http);
      } finally {
         server.stop(0);
         http.shutdownNow();
      }
      System.out.println("Model store smoke checks passed");
   }

   private static void noConsentMakesNoRequest(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "no-consent", spec, http);
      requests.clear();
      require(!store.isInstalled(), "Empty store reported an installed model");
      try {
         store.ensure(false, () -> false);
         throw new AssertionError("ensure(false) returned without a model");
      } catch (ModelStore.ModelMissingException expected) {
         // Required behavior.
      }
      require(requests.isEmpty(), "Network was contacted without consent: " + requests);
      require(!Files.exists(store.modelPath()), "Model file appeared without consent");
      pass("no consent, no request");
   }

   private static void downloadsThroughRedirectAndCaches(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "fresh", spec, http);
      mode = Mode.NORMAL;
      requests.clear();
      Path model = store.ensure(true, () -> false);
      requireContent(model);
      require(requests.equals(List.of("redirect", "blob full")), "Unexpected requests: " + requests);
      require(store.downloadedBytes() == SIZE, "Progress did not reach the full size");
      require(!Files.exists(part(store)), "Partial file was left behind");
      requests.clear();
      require(store.ensure(true, () -> false).equals(model) && requests.isEmpty(), "Installed model was downloaded again");
      require(store(base, "fresh", spec, http).isInstalled(), "A new store did not recognize the installed model");
      pass("download via redirect, then cached");
   }

   private static void resumesPartialDownload(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "resume", spec, http);
      int offset = SIZE / 3;
      Files.createDirectories(part(store).getParent());
      Files.write(part(store), Arrays.copyOf(blob, offset));
      mode = Mode.NORMAL;
      requests.clear();
      requireContent(store.ensure(true, () -> false));
      require(requests.contains("blob range " + offset), "Download did not resume from the partial file: " + requests);
      pass("resume with Range");
   }

   private static void restartsWhenServerIgnoresRange(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "ignore-range", spec, http);
      Files.createDirectories(part(store).getParent());
      byte[] wrongPrefix = new byte[1000];
      Arrays.fill(wrongPrefix, (byte) 7);
      Files.write(part(store), wrongPrefix);
      mode = Mode.IGNORE_RANGE;
      requests.clear();
      requireContent(store.ensure(true, () -> false));
      require(requests.contains("blob full"), "Server response without Range was not used from the start: " + requests);
      pass("restart when Range is ignored");
   }

   private static void rejectsChecksumMismatch(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "corrupt", spec, http);
      mode = Mode.CORRUPT;
      requests.clear();
      expectFailure(store, "error.checksum");
      require(!Files.exists(store.modelPath()), "Corrupted download was installed");
      require(!Files.exists(part(store)), "Corrupted partial file was kept for resuming");
      require(requests.stream().filter(r -> r.startsWith("blob")).count() == 1, "Checksum failure was retried: " + requests);
      pass("checksum mismatch rejected");
   }

   private static void retriesAfterTruncatedResponse(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "truncate", spec, http);
      mode = Mode.TRUNCATE_ONCE;
      firstDone.set(false);
      requests.clear();
      requireContent(store.ensure(true, () -> false));
      require(requests.stream().anyMatch(r -> r.startsWith("blob range ")), "Retry did not resume after truncation: " + requests);
      pass("retry resumes after truncated response");
   }

   private static void retriesAfterStall(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = new ModelStore(base.resolve("cases/stall"), spec, http, Duration.ofSeconds(1));
      mode = Mode.STALL_ONCE;
      firstDone.set(false);
      requests.clear();
      long start = System.nanoTime();
      requireContent(store.ensure(true, () -> false));
      require(requests.stream().filter(r -> r.startsWith("blob")).count() >= 2, "Stalled attempt was not retried: " + requests);
      require(System.nanoTime() - start < Duration.ofSeconds(20).toNanos(), "Stall was not detected promptly");
      pass("stalled transfer aborted and retried");
   }

   private static void failsOnHttpError(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "not-found", spec, http);
      mode = Mode.NOT_FOUND;
      requests.clear();
      expectFailure(store, "error.http [404]");
      require(!Files.exists(store.modelPath()), "Model installed after HTTP error");
      pass("HTTP error reported");
   }

   private static void rejectsCorruptedInstalledModel(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "fresh", spec, http);
      require(store.isInstalled(), "Precondition: model installed");
      byte[] tampered = blob.clone();
      tampered[SIZE / 2] ^= 1;
      Files.write(store.modelPath(), tampered);
      Files.setLastModifiedTime(store.modelPath(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
      require(!store.isInstalled(), "Same-size tampered model was accepted");
      mode = Mode.NORMAL;
      requireContent(store.ensure(true, () -> false));
      pass("tampered installed model re-downloaded");
   }

   private static void stopsWhenCancelled(Path base, ModelStore.ModelSpec spec, HttpClient http) throws Exception {
      ModelStore store = store(base, "cancel", spec, http);
      mode = Mode.NORMAL;
      try {
         store.ensure(true, () -> true);
         throw new AssertionError("Cancelled download completed");
      } catch (IOException expected) {
         // Required behavior.
      }
      require(!Files.exists(store.modelPath()), "Cancelled download was installed");
      pass("cancellation stops download");
   }

   private static void serveBlob(HttpExchange exchange) throws IOException {
      String range = exchange.getRequestHeaders().getFirst("Range");
      Mode current = mode;
      try (exchange) {
         if (current == Mode.NOT_FOUND) {
            requests.add("blob 404");
            exchange.sendResponseHeaders(404, -1);
            return;
         }
         int offset = 0;
         if (range != null && current != Mode.IGNORE_RANGE) {
            offset = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            requests.add("blob range " + offset);
            exchange.getResponseHeaders().add("Content-Range", "bytes " + offset + "-" + (SIZE - 1) + "/" + SIZE);
            exchange.sendResponseHeaders(206, SIZE - offset);
         } else {
            requests.add("blob full");
            exchange.sendResponseHeaders(200, SIZE);
         }
         byte[] body = Arrays.copyOfRange(blob, offset, SIZE);
         if (current == Mode.CORRUPT) body[body.length - 1] ^= 1;
         OutputStream output = exchange.getResponseBody();
         boolean first = !firstDone.getAndSet(true);
         if (current == Mode.TRUNCATE_ONCE && first) {
            output.write(body, 0, body.length / 2);
            output.flush();
            return;
         }
         if (current == Mode.STALL_ONCE && first) {
            output.write(body, 0, body.length / 4);
            output.flush();
            try {
               Thread.sleep(15_000);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
            return;
         }
         output.write(body);
      } catch (IOException ignored) {
         // The client closing a stalled or cancelled transfer is expected.
      }
   }

   private static ModelStore store(Path base, String name, ModelStore.ModelSpec spec, HttpClient http) {
      return new ModelStore(base.resolve("cases").resolve(name), spec, http);
   }

   private static Path part(ModelStore store) {
      return store.modelPath().resolveSibling(store.spec().fileName() + ".part");
   }

   private static void expectFailure(ModelStore store, String messagePart) throws Exception {
      try {
         store.ensure(true, () -> false);
      } catch (IOException e) {
         require(e.getMessage() != null && e.getMessage().contains(messagePart), "Unexpected error: " + e);
         return;
      }
      throw new AssertionError("Download unexpectedly succeeded");
   }

   private static void requireContent(Path model) throws IOException {
      require(Arrays.equals(Files.readAllBytes(model), blob), "Installed model content differs");
   }

   private static void deleteTree(Path root) throws IOException {
      if (!Files.exists(root)) return;
      try (var paths = Files.walk(root)) {
         for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
   }

   private static void pass(String name) {
      System.out.println("ok - " + name);
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
