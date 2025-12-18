package fun.ceroxe.api.management.objectMgr;

import java.io.*;

public class ObjectMgr {
    private ObjectMgr() {
    }

    public static Object loadObj(File file) {
        try {
            if (!file.exists()) {
                return null;
            }
            ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream(file));
            Object obj = objectInputStream.readObject();
            objectInputStream.close();
            return obj;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean writeObject(File file, Object obj) throws IOException {
        if (!file.exists()) {
            file.createNewFile();
        }
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream(file));
        objectOutputStream.writeObject(obj);
        objectOutputStream.close();
        return true;
    }
}
