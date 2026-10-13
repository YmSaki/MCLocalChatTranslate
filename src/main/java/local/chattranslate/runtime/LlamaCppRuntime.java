package local.chattranslate.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns one loopback-only llama-server child process for the Minecraft client. */
public final class LlamaCppRuntime implements AutoCloseable {
   public enum RuntimeState { STARTING, EXTRACTING, LOADING_MODEL, READY, FAILED, STOPPING, STOPPED }
   public record ServerHandle(URI baseUri, String apiKey, Process process) { }

   private final Path gameDir;
   private final Path logPath;
   private final ExecutorService worker = Executors.newSingleThreadExecutor(
      Thread.ofPlatform().daemon().name("localchattranslate-runtime-", 0).factory());
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .proxy(new ProxySelector() {
         @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
         @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
      }).followRedirects(HttpClient.Redirect.NEVER).build();
   private final SecureRandom random = new SecureRandom();
   private volatile RuntimeState state = RuntimeState.STOPPED;
   private volatile ServerHandle handle;
   private WindowsJob job;
   private volatile boolean closed;
   private CompletableFuture<ServerHandle> starting;

   public LlamaCppRuntime(Path gameDir) {
      this.gameDir = gameDir;
      this.logPath = gameDir.resolve("logs/localchattranslate-llama.log");
   }

   public RuntimeState state() {
      if (state == RuntimeState.READY && (handle == null || !handle.process().isAlive())) {
         handle = null;
         state = RuntimeState.FAILED;
      }
      return state;
   }
   public boolean isReady() { return state() == RuntimeState.READY; }

   public synchronized CompletableFuture<ServerHandle> start() {
      return ensureStarted();
   }

   public synchronized CompletableFuture<ServerHandle> ensureStarted() {
      if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Translation runtime is closed"));
      if (isReady()) return CompletableFuture.completedFuture(handle);
      if (starting != null && !starting.isDone()) return starting;
      state = RuntimeState.STARTING;
      starting = CompletableFuture.supplyAsync(() -> {
         try {
            return launch();
         } catch (Exception e) {
            state = RuntimeState.FAILED;
            throw new CompletionException(e);
         }
      }, worker);
      return starting;
   }

   private ServerHandle launch() throws Exception {
      state = RuntimeState.EXTRACTING;
      RuntimeExtractor.ExtractedBundle extracted = RuntimeExtractor.extract(gameDir);
      Files.createDirectories(logPath.getParent());
      byte[] keyBytes = new byte[32];
      random.nextBytes(keyBytes);
      String key = HexFormat.of().formatHex(keyBytes);
      IOException last = null;
      for (int attempt = 0; attempt < 3 && !closed; attempt++) {
         int port = freePort();
         URI baseUri = URI.create("http://127.0.0.1:" + port);
         ProcessBuilder builder = new ProcessBuilder(
            extracted.runtimeDir().resolve("llama-server.exe").toString(),
            "--model", extracted.modelPath().toString(),
            "--host", "127.0.0.1", "--port", Integer.toString(port),
            "--ctx-size", "2048", "--api-key", key,
            "--alias", "translategemma", "--gpu-layers", "99",
            "--no-webui", "--no-jinja", "--chat-template", "gemma");
         builder.directory(extracted.runtimeDir().toFile());
         builder.redirectErrorStream(true);
         builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
         Process process = builder.start();
         attachJob(process);
         ServerHandle candidate = new ServerHandle(baseUri, key, process);
         handle = candidate;
         state = RuntimeState.LOADING_MODEL;
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
         while (!closed && process.isAlive() && System.nanoTime() < deadline) {
            try {
               HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/health"))
                  .timeout(Duration.ofSeconds(2)).GET().build();
               HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
               if (response.statusCode() == 200 && process.isAlive()) {
                  state = RuntimeState.READY;
                  return candidate;
               }
            } catch (IOException ignored) {
               // 503 and connection refusal are both normal while the model loads.
            }
            Thread.sleep(500);
         }
         if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
               process.destroyForcibly();
               process.waitFor();
            }
         }
         releaseJob();
         handle = null;
         last = new IOException("llama-server did not become ready (exit=" + process.exitValue() + "); see " + logPath);
      }
      if (closed) throw new IOException("Translation runtime stopped");
      throw last == null ? new IOException("Could not start llama-server") : last;
   }

   private static int freePort() throws IOException {
      try (ServerSocket socket = new ServerSocket()) {
         socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
         return socket.getLocalPort();
      }
   }

   private synchronized void attachJob(Process process) throws IOException {
      if (closed) {
         process.destroyForcibly();
         throw new IOException("Translation runtime stopped");
      }
      releaseJob();
      try {
         job = new WindowsJob(process);
      } catch (IOException | RuntimeException e) {
         process.destroyForcibly();
         throw e;
      }
   }

   private synchronized void releaseJob() {
      if (job != null) {
         job.close();
         job = null;
      }
   }

   @Override
   public synchronized void close() {
      if (closed) return;
      closed = true;
      state = RuntimeState.STOPPING;
      releaseJob();
      if (handle != null && handle.process().isAlive()) {
         handle.process().destroy();
         try {
            if (!handle.process().waitFor(3, TimeUnit.SECONDS)) handle.process().destroyForcibly();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.process().destroyForcibly();
         }
      }
      handle = null;
      worker.shutdownNow();
      http.shutdownNow();
      state = RuntimeState.STOPPED;
   }
}
