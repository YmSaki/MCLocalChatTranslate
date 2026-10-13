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
import java.util.function.Supplier;

/** Owns one loopback-only llama-server child process for the Minecraft client. */
public final class LlamaCppRuntime implements AutoCloseable {
   public enum RuntimeState { STARTING, EXTRACTING, MODEL_MISSING, DOWNLOADING, LOADING_MODEL, READY, FAILED, STOPPING, STOPPED }
   public record ServerHandle(URI baseUri, String apiKey, Process process) { }
   /** {@code modelPath} overrides the default model; {@code downloadAllowed} records the user's consent to fetch it. */
   public record ModelChoice(boolean downloadAllowed, String modelPath) { }

   private final Path gameDir;
   private final Path logPath;
   private final ExecutorService worker = Executors.newSingleThreadExecutor(
      Thread.ofPlatform().daemon().name("localchattranslate-runtime-", 0).factory());
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .proxy(new ProxySelector() {
         @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
         @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
      }).followRedirects(HttpClient.Redirect.NEVER).build();
   private final HttpClient downloadHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL).build();
   private final Supplier<ModelChoice> modelChoice;
   private final SecureRandom random = new SecureRandom();
   private volatile ModelStore modelStore;
   private volatile String lastError;
   private volatile RuntimeState state = RuntimeState.STOPPED;
   private volatile ServerHandle handle;
   private WindowsJob job;
   private volatile boolean closed;
   /** Incremented by restart(); a launch from an older generation stops and leaves the state alone. */
   private volatile long generation;
   private CompletableFuture<ServerHandle> starting;

   public LlamaCppRuntime(Path gameDir, Supplier<ModelChoice> modelChoice) {
      this.gameDir = gameDir;
      this.modelChoice = modelChoice;
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
   public String lastError() { return lastError; }

   /** Bytes received and expected while {@link RuntimeState#DOWNLOADING}; null when the size is not yet known. */
   public long[] downloadProgress() {
      ModelStore store = modelStore;
      return store == null ? null : new long[]{store.downloadedBytes(), store.spec().size()};
   }

   /** Starts or joins a start, including a model download the user has agreed to. */
   public synchronized CompletableFuture<ServerHandle> start() {
      if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Translation runtime is closed"));
      if (isReady()) return CompletableFuture.completedFuture(handle);
      if (starting != null && !starting.isDone()) return starting;
      long gen = generation;
      state = RuntimeState.STARTING;
      lastError = null;
      starting = CompletableFuture.supplyAsync(() -> {
         try {
            return launch(gen);
         } catch (ModelStore.ModelMissingException e) {
            if (setState(gen, RuntimeState.MODEL_MISSING)) lastError = e.getMessage();
            throw new CompletionException(e);
         } catch (Exception e) {
            if (setState(gen, RuntimeState.FAILED)) lastError = e.getMessage();
            throw new CompletionException(e);
         }
      }, worker);
      return starting;
   }

   /** Stops the current server or start attempt and starts again, e.g. after the model setting changed. */
   public synchronized CompletableFuture<ServerHandle> restart() {
      if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Translation runtime is closed"));
      generation++;
      ServerHandle old = handle;
      handle = null;
      starting = null;
      state = RuntimeState.STOPPED;
      releaseJob();
      if (old != null && old.process().isAlive()) {
         old.process().destroy();
         try {
            if (!old.process().waitFor(3, TimeUnit.SECONDS)) old.process().destroyForcibly();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            old.process().destroyForcibly();
         }
      }
      return start();
   }

   private synchronized boolean setState(long gen, RuntimeState next) {
      if (gen != generation) return false;
      state = next;
      return true;
   }

   private boolean cancelled(long gen) {
      return closed || gen != generation;
   }

   /** Used by translation requests: fails fast instead of waiting for a missing or downloading model. */
   public synchronized CompletableFuture<ServerHandle> ensureStarted() {
      RuntimeState current = state();
      if (current == RuntimeState.DOWNLOADING) {
         return CompletableFuture.failedFuture(new IOException("翻訳モデルをダウンロード中です。"));
      }
      if (current == RuntimeState.MODEL_MISSING && !modelChoice.get().downloadAllowed()) {
         return CompletableFuture.failedFuture(new ModelStore.ModelMissingException());
      }
      return start();
   }

   private ServerHandle launch(long gen) throws Exception {
      String os = System.getProperty("os.name", "");
      String arch = System.getProperty("os.arch", "");
      if (!os.startsWith("Windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
         throw new IOException("内蔵翻訳エンジンは Windows x64 専用です（" + os + " / " + arch + "）。");
      }
      setState(gen, RuntimeState.EXTRACTING);
      RuntimeExtractor.ExtractedBundle extracted = RuntimeExtractor.extract(gameDir);
      Path modelPath = resolveModel(extracted.model(), gen);
      Files.createDirectories(logPath.getParent());
      byte[] keyBytes = new byte[32];
      random.nextBytes(keyBytes);
      String key = HexFormat.of().formatHex(keyBytes);
      IOException last = null;
      for (int attempt = 0; attempt < 3 && !cancelled(gen); attempt++) {
         int port = freePort();
         URI baseUri = URI.create("http://127.0.0.1:" + port);
         ProcessBuilder builder = new ProcessBuilder(
            extracted.runtimeDir().resolve("llama-server.exe").toString(),
            "--model", modelPath.toString(),
            "--host", "127.0.0.1", "--port", Integer.toString(port),
            "--ctx-size", "2048", "--api-key", key,
            "--alias", "translategemma", "--gpu-layers", "99",
            "--no-webui", "--no-jinja", "--chat-template", "gemma");
         builder.directory(extracted.runtimeDir().toFile());
         builder.redirectErrorStream(true);
         builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
         Process process = builder.start();
         ServerHandle candidate = new ServerHandle(baseUri, key, process);
         if (!adopt(gen, candidate)) {
            process.destroyForcibly();
            break;
         }
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
         while (!cancelled(gen) && process.isAlive() && System.nanoTime() < deadline) {
            try {
               HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/health"))
                  .timeout(Duration.ofSeconds(2)).GET().build();
               HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
               if (response.statusCode() == 200 && process.isAlive() && setState(gen, RuntimeState.READY)) {
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
         abandon(gen, candidate);
         last = new IOException("llama-server did not become ready (exit=" + process.exitValue() + "); see " + logPath);
      }
      if (cancelled(gen)) throw new IOException("Translation runtime stopped");
      throw last == null ? new IOException("Could not start llama-server") : last;
   }

   /** Publishes a freshly started server unless a restart or close superseded this launch. */
   private synchronized boolean adopt(long gen, ServerHandle candidate) throws IOException {
      if (cancelled(gen)) return false;
      attachJob(candidate.process());
      handle = candidate;
      state = RuntimeState.LOADING_MODEL;
      return true;
   }

   private synchronized void abandon(long gen, ServerHandle candidate) {
      if (gen != generation || handle != candidate) return;
      releaseJob();
      handle = null;
   }

   private Path resolveModel(ModelStore.ModelSpec spec, long gen) throws IOException, InterruptedException {
      ModelChoice choice = modelChoice.get();
      if (choice.modelPath() != null && !choice.modelPath().isBlank()) {
         Path custom = Path.of(choice.modelPath());
         if (!Files.isRegularFile(custom)) throw new IOException("指定された GGUF が見つかりません: " + custom);
         return custom;
      }
      ModelStore store = new ModelStore(gameDir, spec, downloadHttp);
      if (store.isInstalled()) return store.modelPath();
      if (!choice.downloadAllowed()) throw new ModelStore.ModelMissingException();
      modelStore = store;
      setState(gen, RuntimeState.DOWNLOADING);
      try {
         return store.ensure(true, () -> cancelled(gen));
      } finally {
         modelStore = null;
      }
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
      downloadHttp.shutdownNow();
      state = RuntimeState.STOPPED;
   }
}
