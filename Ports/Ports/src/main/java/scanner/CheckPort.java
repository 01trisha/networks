package scanner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class CheckPort{
    private static final int TIMEOUT = 20000;

    public static boolean isPortOpen(String host, int port){
        try (Socket socket = new Socket()){
            socket.connect(new InetSocketAddress(host, port), TIMEOUT);
            return true;
        } catch (IOException e){
            return false;
        }
    }
}