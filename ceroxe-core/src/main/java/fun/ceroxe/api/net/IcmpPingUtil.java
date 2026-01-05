package fun.ceroxe.api.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class IcmpPingUtil {

    // ★ 修改点 1：返回值由 long 改为 int
    public static int ping(String host, int timeoutMs) {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            System.err.println("DNS解析失败: " + host);
            return -1;
        }

        // 注意：内部方法也要改返回类型，或者在这里强转
        int latency = pingByProcess(address, timeoutMs);

        if (latency == -2) {
            System.out.println("降级使用 Java 原生检测...");
            return pingByJavaNative(address, timeoutMs);
        }

        return latency;
    }

    // ★ 修改点 2：内部方法改为 int
    private static int pingByProcess(InetAddress address, int timeoutMs) {
        try {
            // ... (ProcessBuilder 构建代码省略，与之前一致) ...
            ProcessBuilder processBuilder = new ProcessBuilder();
            // ... (省略中间构建 commands 的代码) ...
            // 为了节省篇幅，这里假设 commands 已经构建好了
            // 实际使用时请把上一版构建 commands 的代码复制过来

            // 下面是针对 int 修改的关键部分
            List<String> commands = buildCommands(address, timeoutMs); // 假设提取了构建逻辑
            processBuilder.command(commands);

            long start = System.nanoTime();
            Process process = processBuilder.start();

            // ... (读取流代码省略) ...
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line);

            boolean finished = process.waitFor(timeoutMs + 1000, TimeUnit.MILLISECONDS);
            long end = System.nanoTime();

            if (finished && process.exitValue() == 0) {
                long parsed = parseLatency(output.toString());
                // ★ 强制转换
                return parsed >= 0 ? (int) parsed : (int) ((end - start) / 1_000_000);
            } else {
                return -1;
            }

        } catch (IOException e) {
            return -2;
        } catch (Exception e) {
            return -1;
        }
    }

    // ★ 修改点 3：Native 方法改为 int
    private static int pingByJavaNative(InetAddress address, int timeoutMs) {
        try {
            long start = System.nanoTime();
            if (address.isReachable(timeoutMs)) {
                // ★ 强制转换
                return (int) ((System.nanoTime() - start) / 1_000_000);
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    private static long parseLatency(String output) {
        // ... (正则逻辑不变) ...
        return -1;
    }

    // 辅助方法：为了代码展示清晰，把构建命令逻辑提出来了（可选）
    private static List<String> buildCommands(InetAddress address, int timeoutMs) {
        // ... 把之前的 command add 逻辑放这里 ...
        return new ArrayList<>();
    }
}