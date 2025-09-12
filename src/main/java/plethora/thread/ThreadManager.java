package plethora.thread;

import plethora.utils.Sleeper;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

public class ThreadManager {
    private CopyOnWriteArrayList<Runnable> runnables = new CopyOnWriteArrayList<>();
    private int waitingTime = 100;//ms

    public ThreadManager(CopyOnWriteArrayList<Runnable> runnables) {
        this.runnables = runnables;
    }

    public ThreadManager(Runnable... runnables) {
        this.runnables.addAll(Arrays.asList(runnables));
    }

    public void addRunnableMethod(Runnable runnable) {
        this.runnables.add(runnable);
    }

    public void startAll() {
        final int[] completeThreadNum = {0};
        for (Runnable runnable : runnables) {
            new Thread(new GcRunnable() {
                @Override
                public void run() {
                    runnable.run();
                    completeThreadNum[0]++;
                    System.gc();
                }
            }, "ThreadManager").start();
        }
        while (completeThreadNum[0] < runnables.size()) {
            Sleeper.sleep(waitingTime);
        }
    }


    public void startAt(int index) {
        new Thread(runnables.get(index)).start();
    }

    public int getWaitingTime() {
        return waitingTime;
    }

    public void setWaitingTime(int waitingTime) {
        if (waitingTime >= 0) {
            this.waitingTime = waitingTime;
        }
    }
}
