package org.example;

import javax.swing.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class Client {
    private String filePath;
    private String serverHost;
    private int serverPort;

    public Client(String filePath, String serverHost, int serverPort){
        this.filePath = filePath;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public boolean sendFile(){
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()){
            System.out.println("файл не найден: " + filePath);
            return false;
        }

        long fileSize = file.length();
        String filename = file.getName();

        try(Socket socket = new Socket(serverHost, serverPort);
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            FileInputStream fileIn = new FileInputStream(file)){

            socket.setSoTimeout(Protocol.SOCKET_TIMEOUT_MS);

            int serverResponse = dataIn.readInt();
            if (serverResponse != Protocol.READY_FOR_METADATA){
                System.out.println("сервер не готов к приему метаданных");
                return false;
            }

            byte[] filenameByte = filename.getBytes(Protocol.STRING_ENCODING);
            dataOut.writeInt(filenameByte.length);
            dataOut.write(filenameByte);
            dataOut.writeLong(fileSize);
            dataOut.flush();


            serverResponse = dataIn.readInt();
            if (serverResponse != Protocol.READY_FOR_FILE){
                handleServerError(serverResponse);
                return false;
            }

            System.out.printf("отправка файла '%s' размером %d байт на %s:%d%n", filename, fileSize, serverHost, serverPort);

            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long totalSent = 0;

            while(totalSent < fileSize){
                int byteRead = fileIn.read(buffer);
                if (byteRead == -1){
                    break;
                }
                dataOut.write(buffer, 0, byteRead);
                totalSent += byteRead;
            }
            dataOut.flush();

            serverResponse = dataIn.readInt();
            if(serverResponse == Protocol.SUCCESS){
                System.out.println("файл успешно отправлен на сервер");
                return true;
            }else{
                handleServerError(serverResponse);
                return false;
            }
        }catch (SocketTimeoutException e){
            System.out.println("таймаут соединения с сервером");
            return false;
        }catch (IOException e){
            System.out.println("ошибка связи с сеовером: " + e.getMessage());
            return false;
        }
    }

    private void handleServerError(int errorCode) {
        switch (errorCode) {
            case Protocol.ERROR_INVALID_FILENAME:
                System.out.println("ошибка: неверное имя файла");
                break;
            case Protocol.ERROR_FILE_TOO_LARGE:
                System.out.println("ошибка: файл слишком большой");
                break;
            case Protocol.ERROR_DISK_FULL:
                System.out.println("ошибка: недостаточно места на сервере");
                break;
            case Protocol.ERROR_TRANSFER_FAILED:
                System.out.println("ошибка: сбой передачи файла");
                break;
            default:
                System.out.println("неизвестная ошибка сервера: " + errorCode);
        }
    }



    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("exmpl args: <file_path> <server_host> <server_port>");
            System.out.println("test.txt localhost 8080");
            return;
        }

        String filePath = args[0];
        String serverHost = args[1];
        int serverPort;

        try{
            serverPort = Integer.parseInt(args[2]);
        }catch (NumberFormatException e){
            System.out.println("неверный формат порта");
            return;
        }

        Client client = new Client(filePath, serverHost, serverPort);
        boolean success = client.sendFile();

        if (success){
            System.exit(0);
        }else{
            System.exit(1);
        }
    }
}