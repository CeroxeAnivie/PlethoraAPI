package fun.ceroxe.api;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

public class EmailTool {

    /**
     * Java 21 Record: 用来承载邮箱配置，简洁且不可变
     */
    public record EmailConfig(
            String host,        // SMTP 主机 (如 smtp.gmail.com)
            int port,           // 端口 (如 587)
            String username,    // 发件人邮箱
            String password,    // 应用专用密码
            String proxyHost,   // 代理 IP (可选，不需要填 null)
            Integer proxyPort   // 代理端口 (可选，不需要填 null)
    ) {}

    /**
     * 发送邮件的核心方法
     * @param config 配置对象
     * @param to 收件人邮箱
     * @param subject 标题
     * @param content 内容 (支持 HTML)
     */
    public static void send(EmailConfig config, String to, String subject, String content) {
        // 1. 设置属性
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", config.host());
        props.put("mail.smtp.port", String.valueOf(config.port()));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2"); // 强制安全协议

        // 2. 代理配置 (如果你在国内连接 Gmail，这部分很关键)
        if (config.proxyHost() != null && !config.proxyHost().isBlank() && config.proxyPort() != null) {
            props.put("mail.smtp.proxy.host", config.proxyHost());
            props.put("mail.smtp.proxy.port", String.valueOf(config.proxyPort()));
            System.out.println("🚀 已启用代理: " + config.proxyHost() + ":" + config.proxyPort());
        }

        // 3. 构建 Session
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username(), config.password());
            }
        });

        // 4. 构建并发送消息
        try {
            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.username()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");

            // 自动检测是 HTML 还是纯文本，这里默认设为 HTML 以支持丰富格式
            message.setContent(content, "text/html; charset=UTF-8");

            Transport.send(message);
            System.out.println("✅ 邮件已发送给: " + to);

        } catch (MessagingException e) {
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }
}