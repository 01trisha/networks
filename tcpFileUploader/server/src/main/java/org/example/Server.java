package org.example;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    private int port;
    private AtomicInteger clientCounter = new AtomicInteger(0);

    public Server(int port){
        this.port = port;
    }

    public void start(){
        File uploadDir = new File(Protocol.UPLOAD_DIR);
        if(!uploadDir.exists()){
            uploadDir.mkdirs();
        }

        try(ServerSocket serverSocket = new ServerSocket(port)){
            System.out.println("сервер запущен на порту: " + port);
            System.out.println("директория для загрузки: " + uploadDir.getAbsolutePath());

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept(); // тут для клиента создается отдельный сокет
                    clientSocket.setSoTimeout(Protocol.SOCKET_TIMEOUT_MS);

                    int clientId = clientCounter.incrementAndGet();
                    System.out.println("Клиент " + clientId + "подключен:" + clientSocket.getInetAddress().getHostAddress());

                    ClientHandler clientHandler = new ClientHandler(clientSocket, clientId);
                    Thread clientThread = new Thread(clientHandler);
                    clientThread.start();
                } catch (IOException e) {
                    System.err.println("ошибка при принятии соединения: " + e.getMessage());
                }
            }
        }catch (IOException e){
            System.err.println("не удалось запустить сервер: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("не указан порт");
            return;
        }

        try {
            int port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                System.out.println("порт должен быть в диапазоне 1-65535");
                return;
            }

            Server server = new Server(port);
            server.start();
        } catch (NumberFormatException e) {
            System.out.println("неверный формат порта");
        }
    }
}