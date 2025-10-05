import plethora.thread.ThreadManager;

import java.util.List;

public class ThreadManagerDemo {

    public static void main(String[] args) {
        System.out.println("🚀 主线程开始...");

        Runnable task1 = () -> {
            System.out.println("✅ 任务1开始");
            try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
            System.out.println("✅ 任务1正常结束");
        };

        Runnable task2 = () -> {
            System.out.println("❌ 任务2开始");
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            throw new RuntimeException("任务2故意失败！");
        };

        Runnable task3 = () -> {
            System.out.println("⚠️ 任务3开始");
            try { Thread.sleep(12000); } catch (InterruptedException ignored) {}
            System.out.println("⚠️ 任务3正常结束");
        };

        ThreadManager manager = new ThreadManager(task1, task2, task3);

        // 阻塞，直到 task1、task2、task3 全部完成（无论成败）
        List<Throwable> errors = manager.start();

        System.out.println("\n🏁 所有任务已完成！");

        if (errors.isEmpty()) {
            System.out.println("🎉 全部成功！");
        } else {
            System.out.println("💥 发现 " + errors.size() + " 个错误：");
            for (int i = 0; i < errors.size(); i++) {
                System.out.println("  [" + (i+1) + "] " + errors.get(i).getMessage());
            }
        }

        System.out.println("👋 主线程结束。");
    }
}