import plethora.security.FilePropertyUtil;

import java.io.File;
import java.io.IOException;

public class FilePropertyTest {
    public static void main(String[] args) throws IOException {
        String a=FilePropertyUtil.getStringSHA256("112233");
        System.out.println("a = " + a);
        System.out.println("a.equals(\"e0bc60c82713f64ef8a57c0c40d02ce24fd0141d5cc3086259c19b1e62a62bea\") = " + a.equals("e0bc60c82713f64ef8a57c0c40d02ce24fd0141d5cc3086259c19b1e62a62bea"));
        String b=FilePropertyUtil.getFileSHA256(new File("D:\\迅雷下载\\VMware-workstation-full-17.6.4-24832109.exe"));
        System.out.println("b = " + b);
    }
}
