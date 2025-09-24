package plethora.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConsoleBackspaceHandler {

    public static void main(String[] args) {
        try {
            System.out.println("请输入文本（支持退格键删除，按Enter提交）:");
            String input = readLineWithBackspace();
            System.out.println("\n您输入的最终内容是: " + input);
        } catch (IOException e) {
            System.err.println("输入错误: " + e.getMessage());
        }
    }

    public static String readLineWithBackspace() throws IOException {
        List<Character> buffer = new ArrayList<>();
        List<Character> deletedChars = new ArrayList<>();
        int ch;

        while ((ch = System.in.read()) != -1) {
            // 处理回车键
            if (ch == '\n' || ch == '\r') {
                break;
            }
            // 处理退格键 (ASCII 8 或 127)
            else if (ch == 8 || ch == 127) {
                if (!buffer.isEmpty()) {
                    // 记录被删除的字符
                    char deletedChar = buffer.remove(buffer.size() - 1);
                    deletedChars.add(deletedChar);

                    // 回退光标并清除字符
                    System.out.print("\b \b");
                }
            } else {
                // 处理普通字符输入
                buffer.add((char) ch);
                System.out.print((char) ch);
            }
        }

        // 输出被删除的字符信息
        if (!deletedChars.isEmpty()) {
            System.out.print("\n被删除的字符: ");
            for (int i = deletedChars.size() - 1; i >= 0; i--) {
                System.out.print("'" + deletedChars.get(i) + "' ");
            }
            System.out.println("(总共删除了 " + deletedChars.size() + " 个字符)");
        }

        // 将字符列表转换为字符串
        StringBuilder sb = new StringBuilder();
        for (char c : buffer) {
            sb.append(c);
        }
        return sb.toString();
    }
}