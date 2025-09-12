package plethora.utils.config;

import plethora.utils.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

public final class LineConfigReader {
    private final File configFile;
    public static final String COMMENT_PREFIX = "#";
    private HashMap<String, String> configElements = null;
    private boolean isLoaded = false;

    public LineConfigReader(File configFile) {
        this.configFile = configFile;
    }

    public void load() throws IOException {
        this.configElements = readAndKillComment(configFile);
        this.isLoaded = true;
    }

    private static HashMap<String, String> readAndKillComment(File file) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
        HashMap<String, String> result = new HashMap<>();
        String str;
        while ((str = bufferedReader.readLine()) != null) {
            if (str.isEmpty() || StringUtils.justHas(str, " ")) {
                continue;
            }
            String a = str.split(COMMENT_PREFIX)[0];
            String[] ele = a.split("=");
            if (ele.length < 2) {
                continue;
            }
            result.put(ele[0], ele[1]);
        }
        return result;
    }


    public HashMap<String, String> getConfigElements() {
        return configElements;
    }

    public File getConfigFile() {
        return configFile;
    }

    public String get(String key) {
        if (isLoaded) {
            if (configElements.containsKey(key)) {
                return configElements.get(key);
            }
        }
        return null;
    }

    public boolean containsValue(String value) {
        if (isLoaded) {
            return configElements.containsValue(value);
        }
        return false;
    }

    public boolean containsKey(String key) {
        if (isLoaded) {
            return configElements.containsKey(key);
        }
        return false;
    }
}