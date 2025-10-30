package plethora.print;

import java.util.Objects;

/**
 * 打印工具类
 * 提供带颜色和样式的控制台输出功能
 */
public final class Printer {
    private Printer() {
        // 工具类，禁止实例化
    }

    /**
     * 获取带格式化的日志字符串
     * @param content 要格式化的内容
     * @param colour 颜色代号：背景颜色代号(41-46)；前景色代号(31-36)
     * @param type 样式代号：0无；1加粗；3斜体；4下划线
     * @return 格式化后的字符串
     */
    public static String getFormatLogString(String content, int colour, int type) {
        Objects.requireNonNull(content, "Content cannot be null");

        boolean hasType = type != 1 && type != 3 && type != 4;
        if (hasType) {
            return String.format("\033[%dm%s\033[0m", colour, content);
        } else {
            return String.format("\033[%d;%dm%s\033[0m", colour, type, content);
        }
    }

    public static void print(String content, int colour, int type) {
        String c = Printer.getFormatLogString(content, colour, type);
        System.out.println(c);
    }

    public static void printNoNewLine(String content, int colour, int type) {
        String c = Printer.getFormatLogString(content, colour, type);
        System.out.print(c);
    }

    public static final class color {
        public static final int RED = 31;
        public static final int YELLOW = 32;
        public static final int ORANGE = 33;
        public static final int BLUE = 34;
        public static final int PURPLE = 35;
        public static final int GREEN = 36;

        private color() {
            // 工具类，禁止实例化
        }
    }

    public static final class style {
        public static final int NONE = 0;
        public static final int BOLD = 1;
        public static final int ITALIC = 3;
        public static final int UNDERSCORE = 4;

        private style() {
            // 工具类，禁止实例化
        }
    }
}