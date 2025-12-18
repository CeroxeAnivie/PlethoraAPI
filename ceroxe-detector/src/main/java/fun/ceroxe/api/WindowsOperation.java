package fun.ceroxe.api;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class WindowsOperation {

    public static boolean isRunning(String exeProcName) {
        Process proc;
        try {
            proc = Runtime.getRuntime().exec("tasklist");
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String info;
            while ((info = br.readLine()) != null) {
                if (info.contains(exeProcName)) {
                    return true;
                }
            }
            proc.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static String runGetString(String procName) {
        try {
            Process proc = Runtime.getRuntime().exec(procName);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            String str;
            StringBuilder stringBuilder = new StringBuilder();
            while ((str = bufferedReader.readLine()) != null) {
                stringBuilder.append(str);
                stringBuilder.append("\n");
            }
            bufferedReader.close();
            return stringBuilder.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Process run(String procName) {
        try {
            return Runtime.getRuntime().exec(procName);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static CopyOnWriteArrayList<String> runGetAsLine(String procName) {
        try {
            Process proc = Runtime.getRuntime().exec(procName);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "GBK"));
            String str;
            CopyOnWriteArrayList<String> result = new CopyOnWriteArrayList<>();
            while ((str = bufferedReader.readLine()) != null) {
                result.add(str);
            }
            bufferedReader.close();
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isOccupied(File file) {
        if (file.isDirectory() || !file.exists()) {
            return false;
        }
        try {
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(file));
            bufferedOutputStream.close();
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    public static void taskKill(String exeName) {
        try {
            String[] cmd = {"tasklist"};
            Process proc = Runtime.getRuntime().exec(cmd);
            Runtime.getRuntime().exec("taskkill /F /im " + exeName);
            proc.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void taskKill(String[] exeNames) {
        for (String exeName : exeNames) {
            try {
                String[] cmd = {"tasklist"};
                Process proc = Runtime.getRuntime().exec(cmd);
                Runtime.getRuntime().exec("taskkill /F /im " + exeName);
                proc.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static ArrayList<String> confirmProcessName(String preName) {
        ArrayList<String> result = new ArrayList<String>();
        try {
            Process proc = Runtime.getRuntime().exec("tasklist");
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String info;
            while ((info = br.readLine()) != null) {
                if (info.contains(preName)) {
                    result.add(info.split(" ")[0]);
                }
            }
            proc.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static void killUnknownProcess(String[] prefixes) {
        try {
            ArrayList<String> a = new ArrayList<>();
            for (String prefix : prefixes) {
                a.addAll(confirmProcessName(prefix));
            }
            for (String s : a) {
                taskKill(s);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void killUnknownProcess(String prefix) {
        WindowsOperation.killUnknownProcess(new String[]{prefix});
    }

    public static String getCurrentUserName() {
        Map<String, String> a = System.getenv();
        return a.get("USERNAME");
    }

    public static String getCurrentComputerName() {
        Map<String, String> a = System.getenv();
        return a.get("COMPUTERNAME");
    }
}