package plethora.print.log;

public class State {
    private LogType type;
    private String subject;
    private String content;

    // 默认构造函数
    public State() {
        this.type = LogType.INFO;
        this.subject = "defaultSubject";
        this.content = "defaultContent";
    }

    // 带参构造函数
    public State(LogType type, String subject, String content) {
        this.type = type;
        this.subject = subject;
        this.content = content;
    }

    // Getters 和 Setters
    public LogType getType() {
        return type;
    }

    public void setType(LogType type) {
        this.type = type;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
