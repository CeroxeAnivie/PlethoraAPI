import plethora.llm.ollama.OllamaOperator;

public class OllamaOperatorDemo {
    public static void main(String[] args) {
        // 1. 创建操作器（本地Ollama服务）
        OllamaOperator operator = new OllamaOperator(
                "http://localhost:11434/api/chat",
                "deepseek-r1:14b"
        );

        // 3. 设置最大历史记录为8轮对话
        operator.setMaxHistoryEntries(8);

        // 4. 发送第一个问题
        OllamaOperator.AiResponse response1 = operator.sendMessage("Java中如何实现线程安全的单例模式？");

        // 5. 输出思考过程和最终答案
        System.out.println("=== 思考过程 ===");
        System.out.println(response1.getThinking());
        System.out.println("\n=== 最终答案 ===");
        System.out.println(response1.getContent());
//
//        // 6. 发送第二个问题（依赖上下文）
//        OllamaOperator.AiResponse response2 = operator.sendMessage("请解释双重检查锁定实现方式");
//
//        // 7. 输出思考过程和最终答案
//        System.out.println("\n\n=== 思考过程 (第二轮) ===");
//        System.out.println(response2.getThinking());
//        System.out.println("\n=== 最终答案 (第二轮) ===");
//        System.out.println(response2.getContent());
//
//        // 8. 查看历史记录
//        System.out.println("\n\n=== 对话历史 ===");
//        operator.getConversationHistory().forEach(msg -> {
//            System.out.println("[" + msg.get("role") + "]: " +
//                    msg.get("content").replace("\n", "\\n"));
//        });
//
//        // 9. 重置对话
//        operator.resetConversation();
//        System.out.println("\n重置后历史记录数: " + operator.getHistorySize());
//
//        // 10. 测试无思考标签的情况
//        OllamaOperator.AiResponse simpleResponse = operator.sendMessage(
//                "Java中的String类有什么特点？"
//        );
//        System.out.println("\n=== 无思考标签的响应 ===");
//        System.out.println("思考内容: " +
//                (simpleResponse.getThinking().isEmpty() ? "无" : "有"));
//        System.out.println("最终答案:\n" + simpleResponse.getContent());
    }
}