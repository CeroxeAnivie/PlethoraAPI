package plethora.management.bufferedFile;

import plethora.exceptions.FileNotFoundException;
import plethora.exceptions.IncorrectTypeException;
import plethora.thread.ThreadManager;
import plethora.utils.ArrayUtils;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CopyOnWriteArrayList;

public class BufferedFile extends File {

    public static StringBuilder separator = new StringBuilder(File.separator);
    private CopyOnWriteArrayList<BufferedFile> fileBox = new CopyOnWriteArrayList();
    private static CopyOnWriteArrayList<BufferedFile> dirBox = new CopyOnWriteArrayList();
    public byte[] buffer = new byte[0];
    private int maxBufferLength = 0;//512mb/s
    private Runnable onStartFileAction = new Runnable() {
        @Override
        public void run() {
        }
    };
    private Runnable onCopyingAction = new Runnable() {
        @Override
        public void run() {

        }
    };
    private Runnable onEndFileAction = new Runnable() {
        @Override
        public void run() {

        }
    };

    public BufferedFile(String pathname) {
        super(pathname);
    }

    public BufferedFile(String parent, String child) {
        super(parent, child);
    }

    public BufferedFile(File parent, String child) {
        super(parent, child);
    }

    public BufferedFile(URI uri) {
        super(uri);
    }

    public static CopyOnWriteArrayList<BufferedFile> getAllData(String[] exceptionPrefix) {
        CopyOnWriteArrayList<BufferedFile> roots = BufferedFile.listAllRoots();
        ArrayUtils.killUnAccessibleItem(roots);
        CopyOnWriteArrayList<BufferedFile> result = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Runnable> runnables = new CopyOnWriteArrayList<>();
        for (BufferedFile root : roots) {

            for (BufferedFile files : root.listBufferedFiles()) {
                for (String prefix : exceptionPrefix) {
                    if (!files.getAbsolutePath().toLowerCase().contains(prefix.toLowerCase())) {
                        Runnable runnable = new Runnable() {
                            @Override
                            public void run() {
                                result.addAll(files.getAllFiles(true));
                            }
                        };
                        runnables.add(runnable);
                    }
                }

            }
        }
        ThreadManager threadManager = new ThreadManager(runnables);
        threadManager.start();
        return result;
    }

    public static CopyOnWriteArrayList<BufferedFile> getAllData(String[] exceptionPrefix, int comparePolicy) {
        CopyOnWriteArrayList<BufferedFile> roots = BufferedFile.listAllRoots();
        ArrayUtils.killUnAccessibleItem(roots);
        CopyOnWriteArrayList<BufferedFile> result = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Runnable> runnables = new CopyOnWriteArrayList<>();
        for (BufferedFile root : roots) {

            for (BufferedFile files : root.listBufferedFiles()) {
                if (comparePolicy == ArrayUtils.WHOLE_WORD_MATCH) {
                    for (String prefix : exceptionPrefix) {
                        if (!files.getAbsolutePath().contains(prefix)) {
                            Runnable runnable = new Runnable() {
                                @Override
                                public void run() {
                                    result.addAll(files.getAllFiles(true));
                                }
                            };
                            runnables.add(runnable);
                        }
                    }
                } else {
                    for (String prefix : exceptionPrefix) {
                        if (!files.getAbsolutePath().toLowerCase().contains(prefix.toLowerCase())) {
                            Runnable runnable = new Runnable() {
                                @Override
                                public void run() {
                                    result.addAll(files.getAllFiles(true));
                                }
                            };
                            runnables.add(runnable);
                        }
                    }
                }

            }
        }
        ThreadManager threadManager = new ThreadManager(runnables);
        threadManager.start();
        return result;
    }

    public static CopyOnWriteArrayList<BufferedFile> getAllData() {
        CopyOnWriteArrayList<BufferedFile> roots = BufferedFile.listAllRoots();
        ArrayUtils.killUnAccessibleItem(roots);
        CopyOnWriteArrayList<BufferedFile> result = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Runnable> runnables = new CopyOnWriteArrayList<>();
        for (BufferedFile root : roots) {

            for (BufferedFile files : root.listBufferedFiles()) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        result.addAll(files.getAllFiles(true));
                    }
                };
                runnables.add(runnable);

            }
        }
        ThreadManager threadManager = new ThreadManager(runnables);
        threadManager.start();
        return result;
    }

    public static CopyOnWriteArrayList<BufferedFile> listAllRoots() {
        File[] roots = File.listRoots();
        return ArrayUtils.fileListToBuffFileList(ArrayUtils.fileArrToFileList(roots));

    }

    protected static void copyFileTo(File file, File desFile, Runnable[] actions, byte[] buffer) throws Exception {
        if (desFile != null) {
            if (!desFile.exists()) {
                File dir = desFile.getParentFile();
                dir.mkdirs();
                desFile.createNewFile();
            }
            if (file.length() != 0) {

                BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(file));
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(desFile));

                int readBytes = 0;

                actions[0].run();

                while ((readBytes = bufferedInputStream.read(buffer)) != -1) {
                    actions[1].run();
                    bufferedOutputStream.write(buffer, 0, readBytes);
                }

                bufferedInputStream.close();
                bufferedOutputStream.close();

                actions[2].run();
            }
        } else {
            throw new NullPointerException("destFile is null !");
        }

    }

    protected static File makeDesFile(File original, File targetDir) {
        StringBuilder stringBuilder = new StringBuilder(targetDir.getPath());
        stringBuilder.append(File.separator).append(original.getName());
        String resultPathName = stringBuilder.toString();
        return new File(resultPathName);
    }

    protected static File makeDesFile(File originalFile, File originalDir, File targetDir) {
        StringBuilder originalFilePath = new StringBuilder(originalFile.getAbsolutePath());
        StringBuilder originalDirPath = new StringBuilder(originalDir.getAbsolutePath());
        StringBuilder targetDirPath = new StringBuilder(targetDir.getPath());
        String resultPathName = targetDirPath.append(File.separator).append(originalDir.getName()).append(originalFilePath.substring(originalDirPath.length())).toString();
        return new File(resultPathName);
    }

    protected static void getAllFiles(File dir, CopyOnWriteArrayList<BufferedFile> fileBox) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    getAllFiles(file, fileBox);
                } else {
                    fileBox.add(BufferedFile.load(file));
                }
            }
        }
    }

    protected static void getAllDirRoots(File dir) {
        File[] files = dir.listFiles();
        if (files.length == 0) {
            dirBox.add(BufferedFile.load(dir));
        } else {
            boolean hasDir = false;
            for (File file : files) {
                if (file.isDirectory()) {
                    hasDir = true;
                    BufferedFile.getAllDirRoots(file);
                }
            }
            if (!hasDir) {
                dirBox.add(BufferedFile.load(dir));
            }
        }
    }

    private static boolean checkDirLegitimacy(File dir) {
        if (dir.isFile()) {
            IncorrectTypeException.throwException(dir);
            return false;
        }
        if (!dir.exists()) {
            FileNotFoundException.throwException(dir);
            return false;
        }
        return true;
    }

    private static boolean checkFileLegitimacy(File file) {
        if (file.isDirectory()) {
            IncorrectTypeException.throwException(file);
            return false;
        }
        if (!file.exists()) {
            FileNotFoundException.throwException(file);
            return false;
        }
        return true;
    }

    public static String getJARPath(Object obj) {
        String path = "";
        try {
            //jar 中没有目录的概念
            URL location = obj.getClass().getProtectionDomain().getCodeSource().getLocation();
            File file = new File(location.getPath());
            path = file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return path;
    }

    public static BufferedFile load(File file) {
        return new BufferedFile(file.getAbsolutePath());
    }

    public static BufferedFile setSuffix(File file, String suffix) {//1.zip
        String[] s = file.getName().split("\\.");
        if (s.length == 1) {
            StringBuilder b = new StringBuilder(file.getAbsolutePath());
            file.renameTo(new File(b.append(suffix).toString()));
            return BufferedFile.load(file);
        } else {
            s[s.length - 1] = suffix.replaceAll("\\.", "");
            StringBuilder b = new StringBuilder(file.getParentFile().getAbsolutePath());
            b.append(File.separator);
            for (int i = 0; i < s.length; i++) {
                String s1 = s[i];
                if (i != 0) {
                    b.append(".");
                }
                b.append(s1);
            }
            file.renameTo(new File(b.toString()));
            return BufferedFile.load(file);
        }
    }

    public void setMaxBufferLength(int maxBufferLength) {
        if (maxBufferLength >= 0) {
            this.maxBufferLength = maxBufferLength;
        } else {
            maxBufferLength = (int) SizeCalculator.mibToByte(512);//512mb/s
        }
    }

    public CopyOnWriteArrayList<BufferedFile> listBufferedFiles() {
        File[] files = super.listFiles();
        if (files != null) {
            return ArrayUtils.fileArrToFileList(files);
        } else {
            return null;
        }

    }

    public boolean isDirRoot() {
        if (this.isFile()) {
            return false;
        } else {
            File[] f = this.listFiles();
            if (f == null) {
                return true;
            }
            for (File file : f) {
                if (file.isDirectory()) {
                    return false;
                }
            }
            return true;
        }
    }

    public void setOptimalSpeed(long bytes) {
        if (maxBufferLength > 0) {
            if (bytes >= 0 && bytes <= maxBufferLength) {
                buffer = new byte[(int) bytes];
            } else {
                buffer = new byte[maxBufferLength];
            }
        } else {
            if (bytes >= Integer.MAX_VALUE) {
                buffer = new byte[Integer.MAX_VALUE];
            } else {
                buffer = new byte[Math.abs((int) bytes)];
            }

        }
    }

    public BufferedFile getRootDir() {
        String[] pathEle = this.getAbsolutePath().split("\\\\");
        if (pathEle.length >= 2) {
            return new BufferedFile(pathEle[0] + BufferedFile.separator + pathEle[1]);
        } else {
            return null;
        }
        //C:\Program Files (x86)\epson
        // C:\Program Files (x86)
        // C:\
    }

    public BufferedFile copyAs(String destFilePath) {
        return this.copyAs(new File(destFilePath));
    }

    public BufferedFile copyAs(File destFile) {
        try {
            if (this.exists()) {
                if (this.isFile()) {
                    this.setOptimalSpeed(this.length());
                    BufferedFile.copyFileTo(this, destFile, new Runnable[]{onStartFileAction, onCopyingAction, onEndFileAction}, this.buffer);
                    return BufferedFile.load(destFile);
                } else {
                    BufferedFile file = BufferedFile.load(destFile.getParentFile());
                    StringBuilder s = new StringBuilder(file.getAbsolutePath());
                    s.append(File.separator).append(this.getName());
                    BufferedFile a = new BufferedFile(s.toString());
                    this.copyTo(a.getParentFile());
                    a.renameTo(destFile);
                    return a;
                }
            } else {
                FileNotFoundException.throwException(this);
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean createNewFile() {
        try {
            this.getParentFile().mkdirs();
            return super.createNewFile();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public BufferedFile copyTo(String dirPath) {
        return this.copyTo(new File(dirPath));
    }

    public BufferedFile copyTo(File destDir) {
        try {
            if (this.exists()) {
                if (this.isFile()) {
                    File destFile = makeDesFile(this, destDir);
                    this.setOptimalSpeed(this.length());
                    BufferedFile.copyFileTo(this, destFile, new Runnable[]{onStartFileAction, onCopyingAction, onEndFileAction}, this.buffer);
                    StringBuilder stringBuilder = new StringBuilder(destDir.getAbsolutePath());
                    return new BufferedFile(stringBuilder.append(this.getName()).toString());
                } else {
                    CopyOnWriteArrayList<BufferedFile> files = this.getAllFiles(true);
                    for (int i = 0; i < files.size(); i++) {
                        File original = files.get(i);
                        File destFile = makeDesFile(original, this, destDir);
                        this.setOptimalSpeed(original.length());
                        BufferedFile.copyFileTo(original, destFile, new Runnable[]{onStartFileAction, onCopyingAction, onEndFileAction}, this.buffer);
                    }
                    StringBuilder stringBuilder = new StringBuilder(destDir.getAbsolutePath());
                    return new BufferedFile(stringBuilder.append(this.getName()).toString());
                }
            } else {
                FileNotFoundException.throwException(this);
                return null;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public CopyOnWriteArrayList<BufferedFile> getAllFiles(boolean containItself) {

        if (BufferedFile.checkDirLegitimacy(this)) {
            fileBox = new CopyOnWriteArrayList<>();
            BufferedFile.getAllFiles(this, fileBox);
            return fileBox;
        } else {
            CopyOnWriteArrayList<BufferedFile> a = new CopyOnWriteArrayList<>();
            if (containItself) {
                a.add(this);
            }
            return a;
        }
    }

    public CopyOnWriteArrayList<BufferedFile> getAllFiles() {
        return this.getAllFiles(false);
    }

    public CopyOnWriteArrayList<BufferedFile> getAllDirRoots() {
        if (BufferedFile.checkDirLegitimacy(this)) {
            dirBox = new CopyOnWriteArrayList<>();
            BufferedFile.getAllDirRoots(this);
            return dirBox;
        } else {
            return null;
        }
    }

    public boolean delete() {
        try {
            if (this.exists()) {
                if (this.isFile()) {
                    return super.delete();
                } else {
                    CopyOnWriteArrayList<BufferedFile> files = this.getAllFiles(true);
                    for (File file : files) {
                        File a = file.getAbsoluteFile();
                        a.delete();
                    }//delete all file first

                    while (this.exists()) {
                        CopyOnWriteArrayList<BufferedFile> dirs = this.getAllDirRoots();
                        for (int i = 0; i < dirs.size(); i++) {
                            File a = dirs.get(i).getAbsoluteFile();
                            a.delete();
                        }
                    }
                    return true;
                }
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEmpty() {
        if (this.isFile()) {
            return false;
        } else {
            return this.listFiles() != null;
        }
    }

    public boolean isAvailable() {
        return this.canExecute() && this.canAccess();
    }

    public boolean canAccess() {
        return this.exists() && this.canRead() && this.canWrite();
    }

    public boolean equals(BufferedFile bf) {
        return (this.getAbsolutePath().equals(bf.getAbsolutePath()));
    }

    public BufferedFile getParentFile() {
        return BufferedFile.load(super.getParentFile());
    }

    public boolean equals(String path) {
        return (this.getAbsolutePath().equals(new File(path).getAbsolutePath()));
    }

    public BufferedFile setName(String str) {
        StringBuilder s = new StringBuilder(this.getParentFile().getAbsolutePath());
        s.append(File.separator).append(str);
        String resultPath = s.toString();
        BufferedFile resultFile = new BufferedFile(resultPath);
        this.renameTo(resultFile);
        return resultFile;
    }

    public void setOnStartFile(Runnable runnable) {
        this.onStartFileAction = runnable;
    }

    public void setOnCopyAction(Runnable runnable) {
        this.onCopyingAction = runnable;
    }

    public void setEndFile(Runnable runnable) {
        this.onEndFileAction = runnable;
    }

    public void gc() {
        System.gc();
    }
}