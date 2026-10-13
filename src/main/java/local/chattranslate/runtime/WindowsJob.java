package local.chattranslate.runtime;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;

/** A kill-on-close Windows job ensures the native server dies even if Minecraft is killed. */
final class WindowsJob implements AutoCloseable {
   private static final int EXTENDED_LIMIT_INFORMATION = 9;
   private static final int KILL_ON_JOB_CLOSE = 0x00002000;
   private static final int PROCESS_SET_QUOTA = 0x0100;
   private static final int PROCESS_TERMINATE = 0x0001;
   // x64 JOBOBJECT_EXTENDED_LIMIT_INFORMATION: 64-byte basic limits,
   // 48-byte IO counters and four 8-byte SIZE_T fields.
   private static final int EXTENDED_LIMIT_SIZE_X64 = 144;
   private static final int LIMIT_FLAGS_OFFSET_X64 = 16;
   private static final Kernel32Job API = Native.load("kernel32", Kernel32Job.class);

   private Pointer handle;

   private interface Kernel32Job extends StdCallLibrary {
      Pointer CreateJobObjectW(Pointer securityAttributes, WString name);
      boolean SetInformationJobObject(Pointer job, int infoClass, Pointer information, int length);
      Pointer OpenProcess(int access, boolean inheritHandle, int processId);
      boolean AssignProcessToJobObject(Pointer job, Pointer process);
      boolean CloseHandle(Pointer object);
   }

   WindowsJob(Process process) throws IOException {
      if (Native.POINTER_SIZE != 8) throw new IOException("Only Windows x64 is supported");
      handle = API.CreateJobObjectW(null, null);
      if (handle == null) throw winError("CreateJobObjectW");
      try {
         Memory limits = new Memory(EXTENDED_LIMIT_SIZE_X64);
         limits.clear();
         limits.setInt(LIMIT_FLAGS_OFFSET_X64, KILL_ON_JOB_CLOSE);
         if (!API.SetInformationJobObject(handle, EXTENDED_LIMIT_INFORMATION, limits, EXTENDED_LIMIT_SIZE_X64)) {
            throw winError("SetInformationJobObject");
         }
         Pointer child = API.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, Math.toIntExact(process.pid()));
         if (child == null) throw winError("OpenProcess");
         try {
            if (!API.AssignProcessToJobObject(handle, child)) throw winError("AssignProcessToJobObject");
         } finally {
            API.CloseHandle(child);
         }
      } catch (IOException | RuntimeException e) {
         close();
         throw e;
      }
   }

   private static IOException winError(String call) {
      return new IOException(call + " failed: Win32 error " + Native.getLastError());
   }

   @Override
   public synchronized void close() {
      if (handle != null) {
         API.CloseHandle(handle);
         handle = null;
      }
   }
}
