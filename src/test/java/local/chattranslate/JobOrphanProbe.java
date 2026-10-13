package local.chattranslate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import local.chattranslate.runtime.LlamaCppRuntime;

/** Test-only process that is deliberately killed to check Windows job cleanup. */
public final class JobOrphanProbe {
   public static void main(String[] args) throws Exception {
      Path gameDir = Path.of("build/runtime-smoke").toAbsolutePath();
      LlamaCppRuntime runtime = new LlamaCppRuntime(gameDir, () -> new LlamaCppRuntime.ModelChoice(false, null));
      LlamaCppRuntime.ServerHandle server = runtime.start().get(150, TimeUnit.SECONDS);
      Path pids = Path.of("build/orphan-probe-pids.txt").toAbsolutePath();
      Files.writeString(pids, ProcessHandle.current().pid() + "\n" + server.process().pid() + "\n");
      System.out.println("Orphan probe READY: JVM=" + ProcessHandle.current().pid()
         + " server=" + server.process().pid());
      System.out.flush();
      while (true) Thread.sleep(1000);
   }
}
