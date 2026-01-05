package fun.ceroxe.api;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程守护启动器 (独立封装版)
 * <p>
 * 移除了对 jna-platform 复杂类的直接依赖，将所需的 Windows API 定义内嵌，
 * 彻底解决 IDE 找不到符号或类定义的问题。
 */
public class ProcessContainer {

    private static final boolean IS_WINDOWS;
    private static final boolean IS_LINUX_OR_MAC;

    // 我们自己定义的 Kernel32 接口实例
    private static final MyKernel32 KERNEL32;
    // 全局 Job 句柄
    private static final WinNT.HANDLE WINDOWS_JOB_HANDLE;

    static {
        String os = System.getProperty("os.name").toLowerCase();
        IS_WINDOWS = os.contains("win");
        IS_LINUX_OR_MAC = os.contains("nux") || os.contains("mac") || os.contains("nix");

        if (IS_WINDOWS) {
            // 加载 Kernel32
            KERNEL32 = Native.load("kernel32", MyKernel32.class, W32APIOptions.DEFAULT_OPTIONS);

            // 1. 创建 Job Object
            WINDOWS_JOB_HANDLE = KERNEL32.CreateJobObject(null, null);
            if (WINDOWS_JOB_HANDLE == null) {
                throw new IllegalStateException("Job Object 创建失败，错误码: " + KERNEL32.GetLastError());
            }

            // 2. 配置 Job 属性 (使用内部定义的 Structure)
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION jeli = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            jeli.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000

            // 关键：手动写入 Structure 内存
            jeli.write();

            // 9 = JobObjectExtendedLimitInformation class
            if (!KERNEL32.SetInformationJobObject(WINDOWS_JOB_HANDLE, 9, jeli, jeli.size())) {
                throw new IllegalStateException("Job 属性配置失败，错误码: " + KERNEL32.GetLastError());
            }
        } else {
            KERNEL32 = null;
            WINDOWS_JOB_HANDLE = null;
        }
    }

    // =========================================================================
    // 核心功能区
    // =========================================================================

    public static Process start(String... command) throws IOException {
        return start(new ProcessBuilder(command));
    }

    public static Process start(ProcessBuilder builder) throws IOException {
        if (IS_LINUX_OR_MAC) {
            wrapCommandForLinux(builder);
        }

        Process process = builder.start();

        if (IS_WINDOWS) {
            bindProcessToWindowsJob(process);
        }

        // Java 层双重保险
        ProcessHandle handle = process.toHandle();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignored) {
            }
        }));

        return process;
    }

    private static void bindProcessToWindowsJob(Process process) {
        if (KERNEL32 == null || WINDOWS_JOB_HANDLE == null) return;

        long pid = process.pid();
        // 0x1F0FFF = PROCESS_ALL_ACCESS
        WinNT.HANDLE processHandle = KERNEL32.OpenProcess(0x1F0FFF, false, (int) pid);

        if (processHandle == null) return;

        try {
            KERNEL32.AssignProcessToJobObject(WINDOWS_JOB_HANDLE, processHandle);
        } finally {
            KERNEL32.CloseHandle(processHandle);
        }
    }

    // =========================================================================
    // Linux Watchdog 逻辑 (保持不变)
    // =========================================================================
    private static void wrapCommandForLinux(ProcessBuilder builder) {
        List<String> originalCmd = builder.command();
        if (originalCmd.isEmpty()) return;

        long javaPid = ProcessHandle.current().pid();
        String script =
                "java_pid=" + javaPid + ";\n" +
                        "\"$@\" & \n" +
                        "child_pid=$!;\n" +
                        "(\n" +
                        "  while kill -0 $java_pid 2>/dev/null; do\n" +
                        "    sleep 0.5;\n" +
                        "  done;\n" +
                        "  kill -9 $child_pid 2>/dev/null\n" +
                        ") & \n" +
                        "watcher_pid=$!;\n" +
                        "trap \"kill -TERM $child_pid\" TERM INT;\n" +
                        "wait $child_pid;\n" +
                        "exit_code=$?;\n" +
                        "kill $watcher_pid 2>/dev/null;\n" +
                        "exit $exit_code;";

        List<String> wrappedCmd = new ArrayList<>();
        wrappedCmd.add("/bin/sh");
        wrappedCmd.add("-c");
        wrappedCmd.add(script);
        wrappedCmd.add("_");
        wrappedCmd.addAll(originalCmd);
        builder.command(wrappedCmd);
    }

    // =========================================================================
    // JNA 内部定义 (手动映射，脱离 jna-platform 依赖)
    // =========================================================================

    /**
     * 自定义 Kernel32 接口，只包含我们需要的方法
     */
    public interface MyKernel32 extends StdCallLibrary {
        WinNT.HANDLE CreateJobObject(Pointer lpJobAttributes, String lpName);

        boolean SetInformationJobObject(WinNT.HANDLE hJob, int JobObjectInfoClass, Structure lpJobObjectInfo, int cbJobObjectInfoLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE hJob, WinNT.HANDLE hProcess);

        WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        boolean CloseHandle(WinNT.HANDLE hObject);

        int GetLastError();
    }

    /**
     * 手动定义的 BasicLimitInformation 结构体
     */
    @Structure.FieldOrder({"PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
            "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit",
            "Affinity", "PriorityClass", "SchedulingClass"})
    public static class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public long MinimumWorkingSetSize;
        public long MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public long Affinity;
        public int PriorityClass;
        public int SchedulingClass;
    }

    /**
     * 手动定义的 ExtendedLimitInformation 结构体
     */
    @Structure.FieldOrder({"BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
            "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed"})
    public static class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;
    }

    /**
     * 辅助结构体 IO_COUNTERS
     */
    @Structure.FieldOrder({"ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount"})
    public static class IO_COUNTERS extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
    }
}