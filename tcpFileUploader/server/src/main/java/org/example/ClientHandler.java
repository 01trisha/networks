package org.example;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

public class ClientHandler implements Runnable{
    private Socket clientSocket;
    private int clientId;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;

    public ClientHandler(Socket socket, int clientId){
        this.clientSocket = socket;
        this.clientId = clientId;
    }


    @Override
    public void run() {
        try{
            dataIn = new DataInputStream(clientSocket.getInputStream());
            dataOut = new DataOutputStream(clientSocket.getOutputStream());

            dataOut.writeInt(Protocol.READY_FOR_METADATA);
            dataOut.flush();

            int filenameLength = dataIn.readInt();
            if (filenameLength <= 0 || filenameLength > Protocol.MAX_FILENAME_LENGTH){
                sendError(Protocol.ERROR_INVALID_FILENAME, "неверная длина имени файла");
                return;
            }

            byte[] filenameBytes = new byte[filenameLength];
            dataIn.readFully(filenameBytes);
            String filename = new String(filenameBytes, Protocol.STRING_ENCODING);
            if (!isFilenameSafe(filename)){
                sendError(Protocol.ERROR_INVALID_FILENAME, "небезопасное имя файла");
                return;
            }

            long fileSize = dataIn.readLong();
            if (fileSize < 0 || fileSize > Protocol.MAX_FILE_SIZE){
                sendError(Protocol.ERROR_FILE_TOO_LARGE, "слишком большой файл");
            }

            System.out.printf("клииент %d: файл '%s', размер: %d байт%n", clientId, filename, fileSize);

            dataOut.writeInt(Protocol.READY_FOR_FILE);
            dataOut.flush();

            if (reveiveFile(filename, fileSize)){
                dataOut.writeInt(Protocol.SUCCESS);
                dataOut.flush();
                System.out.println("клиент " + clientId + ": файл успешно получен");
            }else{
                sendError(Protocol.ERROR_TRANSFER_FAILED, "ошибка передачи файла");
            }

        } catch (SocketTimeoutException e){
            System.err.println("клиент " + clientId + ": вышло время ожидания");
        } catch (IOException e) {
            System.err.println("клиент " + clientId + ": ошибка ввода вывода - " + e.getMessage());
        }finally {
            closeResources();
        }
    }

    private void closeResources(){
        try{
            if (dataIn != null){
                dataIn.close();
            } if (dataOut != null){
                dataOut.close();
            } if (clientSocket != null){
                clientSocket.close();
            }
        }catch (IOException e){
            System.out.println("ошибка закрытия ресурсов клиента " + clientId + ": " + e.getMessage());
        }
    }

    private boolean reveiveFile(String filename, long expectedSize){
        Path filePath = Paths.get(Protocol.UPLOAD_DIR, filename);

        try(FileOutputStream fileOut = new FileOutputStream(filePath.toFile())){
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long totalReceived = 0;
            long startTime = System.currentTimeMillis();

            AtomicLong lastPeriodBytes = new AtomicLong(0);
            AtomicLong totalBytes = new AtomicLong(0);

            Thread speedUpload = new Thread(() ->{
                while(!Thread.currentThread().isInterrupted() && totalBytes.get() < expectedSize){
                    try{
                        Thread.sleep(Protocol.SPEED_REPORT_INTERVAL_MS);

                        long currentTime = System.currentTimeMillis();
                        long elapsedTime = currentTime  - startTime;
                        long currentTotalBytes = totalBytes.get();
                        long currentPeriodBytes = lastPeriodBytes.getAndSet(0);

                        if(elapsedTime > 0){
                            double instantSpeed = (currentPeriodBytes*1000.0)/Protocol.SPEED_REPORT_INTERVAL_MS;
                            double averageSpeed = (currentTotalBytes*1000.0)/elapsedTime;
                            System.out.printf("клиент %d: мгновенная скорость - %.2f KB/s, средняя скорость - %.2f KB/s%n", clientId, instantSpeed/1024, averageSpeed/1024);
                        }
                    }catch (InterruptedException e){
                        break;
                    }
                }
            });

            speedUpload.start();

            while(totalReceived < expectedSize){
                int bytesToRead = (int) Math.min(Protocol.BUFFER_SIZE, expectedSize - totalReceived);
                int bytesRead = dataIn.read(buffer, 0, bytesToRead);

                if (bytesRead == -1){
                    break;
                }

                fileOut.write(buffer, 0, bytesRead);
                totalReceived += bytesRead;

                totalBytes.addAndGet(bytesRead);
                lastPeriodBytes.addAndGet(bytesRead);
            }

            speedUpload.interrupt();

            if (totalReceived != expectedSize){
                System.err.printf("клиент %d: несовпадение размеров файлов, ожидалось %d, получено %d%n", clientId, expectedSize, totalReceived);
                Files.deleteIfExists(filePath);
                return false;
            }

            long endTime = System.currentTimeMillis();
            double totalSpeed = (totalReceived*1000.0)/(endTime - startTime);
            System.out.printf("клиент %d: передача завершена за %.2f сек, средняя скорость %.2f KB/s%n", clientId, (endTime - startTime)/1000.0, totalSpeed/1024);

            return true;

        }catch (IOException e){
            System.err.println("клиент " + clientId + ": ошибка при сохранении - " + e.getMessage());
            try{
                Files.deleteIfExists(filePath);
            }catch (IOException ex){

            }
            return  false;
        }
    }

    private boolean isFilenameSafe(String filename){
        if (filename == null || filename.isEmpty()){
            return false;
        }

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")){
            return false;
        }

        try{
            Path path = Paths.get(Protocol.UPLOAD_DIR, filename).normalize();
            return path.startsWith(Paths.get(Protocol.UPLOAD_DIR).normalize());
        }catch (InvalidPathException e){
            return false;
        }
    }
    private void sendError(int errorCode, String message){
        try{
            dataOut.writeInt(errorCode);
            dataOut.flush();
            System.out.println("клиет " + clientId + ": " + message);
        } catch (IOException e) {
            System.err.println("не удалось отправить ошибку клиенту " + clientId);
        }
    }
}
