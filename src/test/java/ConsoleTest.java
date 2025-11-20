import plethora.utils.MyConsole;

import java.util.List;

public class ConsoleTest {
    public static int A = 1;

    public static void print() {
        System.out.println("A = " + A);
    }

    public static void main(String[] args) throws Exception {
        MyConsole console = new MyConsole("MyApp");

        // 注册一个回显命令
        console.registerCommand("echo", "回显输入的文本", (List<String> params) -> {
            if (params.isEmpty()) {
                console.warn("1", "用法: echo <文本>");
                A = 2;
                print();
            } else {
                console.log("1", "回显: " + String.join(" ", params));
            }
        });
        console.registerCommand(null, "不存在的指令", (List<String> params) -> {
            console.error("1", "ABSIOAJDISDW9Q03J0EDQW3");
        });

        // 注册一个模拟错误的命令
        console.registerCommand("fail", "触发一个模拟错误", (List<String> params) -> {
            console.error("1", "这是一个模拟的运行时错误", new RuntimeException("Oops! Something went wrong."));
        });

        // 注册一个连接命令
        console.registerCommand("connect", "连接到指定地址", (List<String> params) -> {
            if (params.size() != 1) {
                console.warn("1", "用法: connect <address>");
                return;
            }
            String address = params.get(0);
            console.log("1", "正在连接到: " + address);
            // 模拟连接成功
            console.log("1", "✅ 成功连接到: " + address);
        });

        // 启动控制台
        console.start();
        String str = console.execute("help");
        System.out.println("\nstr = " + str);
    }
}