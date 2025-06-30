package plethora.print.log;

import plethora.os.detect.OSDetector;
import plethora.print.Printer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Loggist {

    public static int WINDOWS_VERSION = -1;
    private final File logFile;
    private BufferedWriter bufferedWriter;
    private boolean isOpenChannel = false;

    public Loggist(File logFile) {
        Loggist.WINDOWS_VERSION = OSDetector.getWindowsVersion();
        this.logFile = logFile;
        if (!logFile.exists()) {
            try {
                logFile.getParentFile().mkdirs();
                logFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        this.openWriteChannel();
    }

    public void disableColor() {
        WINDOWS_VERSION = 1000;
    }

    public File getLogFile() {
        return logFile;
    }

    public void openWriteChannel() {
        try {
            this.bufferedWriter = Files.newBufferedWriter(Paths.get(logFile.toURI()), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.isOpenChannel = true;
        } catch (IOException e) {
            e.printStackTrace();
            this.isOpenChannel = false;
        }
    }

    public void closeWriteChannel() {
        if (bufferedWriter != null) {
            try {
                this.bufferedWriter.close();
                this.bufferedWriter = null;
                this.isOpenChannel = false;
            } catch (IOException e) {
                e.printStackTrace();
                this.bufferedWriter = null;
                this.isOpenChannel = false;
            }
        }
    }

    public void write(String str,boolean isNewLine) {
        if (isOpenChannel) {
            try {
                bufferedWriter.write(str);
                if (isNewLine){
                    bufferedWriter.newLine();
                }
                bufferedWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
                this.isOpenChannel = false;
            }
        }
    }

    public void say(State state) {
        if (WINDOWS_VERSION == -1 ||WINDOWS_VERSION >= 22000 ) { // is linux or mac or Windows 11
            System.out.println(this.getLogString(state));
            this.write(this.getNoColString(state),true);
        } else { // is Windows 10 or less
            System.out.println(this.getNoColString(state));
            this.write(this.getNoColString(state),true);
        }
    }

    public void sayNoNewLine(State state) {
        if (WINDOWS_VERSION == -1 ||WINDOWS_VERSION >= 22000 ) { // is linux or mac or Windows 11
            System.out.print(this.getLogString(state));
            this.write(this.getNoColString(state),false);
        } else { // is Windows 10 or less
            System.out.print(this.getNoColString(state));
            this.write(this.getNoColString(state),false);
        }
    }

    // 使用 Java 标准库 LocalDateTime 获取当前时间，格式化与原始版本保持一致
    public String getLogString(State state) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
        String time = LocalDateTime.now().format(formatter);
        StringBuilder result = new StringBuilder(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        result.append(Printer.getFormatLogString(time, Printer.color.YELLOW, Printer.style.NONE));
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));
        result.append("  ");

        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        if (state.getType() == LogType.ERROR) {
            result.append(Printer.getFormatLogString("ERROR", Printer.color.RED, Printer.style.NONE));
        } else if (state.getType() == LogType.INFO) {
            result.append(Printer.getFormatLogString("INFO", Printer.color.GREEN, Printer.style.NONE));
        } else {
            result.append(Printer.getFormatLogString("WARNING", Printer.color.YELLOW, Printer.style.NONE));
        }
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));

        result.append(" ");
        result.append(Printer.getFormatLogString("[", Printer.color.PURPLE, Printer.style.NONE));
        result.append(Printer.getFormatLogString(state.getSubject(), Printer.color.ORANGE, Printer.style.NONE));
        result.append(Printer.getFormatLogString("]", Printer.color.PURPLE, Printer.style.NONE));

        result.append(" ");
        result.append(state.getContent());
        return result.toString();
    }

    public String getNoColString(State state) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
        String time = LocalDateTime.now().format(formatter);
        StringBuilder result = new StringBuilder("[");
        result.append(time);
        result.append("]");
        result.append("  ");

        result.append("[");
        if (state.getType() == LogType.ERROR) {
            result.append("ERROR");
        } else if (state.getType() == LogType.INFO) {
            result.append("INFO");
        } else {
            result.append("WARNING");
        }
        result.append("]");

        result.append(" ");
        result.append("[");
        result.append(state.getSubject());
        result.append("]");

        result.append(" ");
        result.append(state.getContent());
        return result.toString();
    }

    public boolean isOpenChannel() {
        return isOpenChannel;
    }

    protected void gc() {
        this.closeWriteChannel();
        System.gc();
    }
}
