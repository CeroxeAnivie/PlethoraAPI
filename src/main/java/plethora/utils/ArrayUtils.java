package plethora.utils;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

public class ArrayUtils {
    public static CopyOnWriteArrayList<String> toCopyOnWriteArrayListWithLoop(String[] stringArray) {
        // 创建一个空的 CopyOnWriteArrayList
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        // 检查 null
        if (stringArray != null) {
            // 遍历数组，并将每个元素添加到列表中
            list.addAll(Arrays.asList(stringArray));
        }

        return list;
    }
}
