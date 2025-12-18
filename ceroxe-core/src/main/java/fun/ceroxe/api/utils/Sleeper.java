package fun.ceroxe.api.utils;

public final class Sleeper {
    private Sleeper() {
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception ignore) {
        }
    }
}
