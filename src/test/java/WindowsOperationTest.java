import plethora.os.windowsSystem.WindowsOperation;

public class WindowsOperationTest {
    public static void main(String[] args) {
        String str=WindowsOperation.runGetString("cmd /c ver");
        System.out.println("str = " + str);
    }
}
