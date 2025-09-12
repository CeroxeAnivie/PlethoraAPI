import plethora.net.SecureServerSocket;
import plethora.net.SecureSocket;

import java.util.Arrays;

public class AESTest {
    public static void main(String[] args) {
        new Thread(()->{
            try {
                SecureServerSocket serverSocket=new SecureServerSocket(7766);
                SecureSocket socket=serverSocket.accept();
                socket.sendStr("你好123ABbc");
                socket.sendByte(new byte[]{3,4,5,6,7});
                socket.sendInt(11223344);
                socket.sendStr(null);
                socket.sendByte(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
        new Thread(()->{
            try {
                SecureSocket socket=new SecureSocket("127.0.0.1",7766);
                System.out.println("socket.receiveStr() = " + socket.receiveStr());
                System.out.println(Arrays.toString(socket.receiveByte()));
                System.out.println(socket.receiveInt());
                System.out.println("socket = " + socket.getInetAddress().getHostAddress());
                System.out.println("socket.receiveStr() = " + socket.receiveStr());
                System.out.println("Arrays.toString(socket.receiveByte()) = " + Arrays.toString(socket.receiveByte()));
            }catch (Exception e){
                throw new RuntimeException(e);
            }
        }).start();
    }
}
