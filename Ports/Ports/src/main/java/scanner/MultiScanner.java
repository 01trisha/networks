package scanner;

import java.util.*;
import java.util.concurrent.*;

public class MultiScanner{
    private static final int THREAD_POOL_SIZE = 20;
    private final ServiceLoader serviceLoader;

    public MultiScanner(ServiceLoader serviceLoader){
        this.serviceLoader = serviceLoader;
    }

    public void scan(int startPort, int endPort){
        try{
            validatePortRange(startPort, endPort);
            System.out.println("Сканирование портов " + startPort + "-" + endPort + "...");

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
            List<Future<PortResult>> futures = new ArrayList<>();

            for (int port = startPort; port <= endPort; port++){
                int finalPort = port;
                Callable<PortResult> task = () -> scanSinglePort(finalPort);
                futures.add(executor.submit(task));
            }

            processResults(futures, endPort - startPort + 1);
            executor.shutdown();

        } catch (IllegalArgumentException e){
            System.out.println("Ошибка ввода: " + e.getMessage());
        } catch (Exception e){
            System.out.println("Внезапная ошибка: " + e.getMessage());
        }
    }

    private PortResult scanSinglePort(int port){
        boolean isOpen = CheckPort.isPortOpen("127.0.0.1", port);
        String service = serviceLoader.getServiceName(port);
        return new PortResult(port, isOpen, service);
    }

    private void validatePortRange(int startPort, int endPort){
        if (startPort < 1 || endPort > 65535){
            throw new IllegalArgumentException("Диапазон портов должен быть от 1 до 65535");
        }
        if (startPort > endPort){
            throw new IllegalArgumentException("Начальный порт не может быть больше конечного");
        }
    }

    private void processResults(List<Future<PortResult>> futures, int totalPorts){
        List<PortResult> openPorts = new ArrayList<>();

        for (Future<PortResult> future : futures){
            try{
                PortResult result = future.get();
                if (result.isOpen()){
                    openPorts.add(result);
                }
            } catch (Exception e){
                System.err.println("Ошибка: " + e.getMessage());
            }
        }

        openPorts.sort(Comparator.comparingInt(PortResult::getPort));

        System.out.println("\nОткрытые порты:");
        openPorts.forEach(port -> System.out.printf("Порт %d: %s%n", port.getPort(), port.getService())
        );

        System.out.printf("\nНайдено открытых портов: %d из %d%n", openPorts.size(), totalPorts);
    }

    private static class PortResult{
        private final int port;
        private final boolean isOpen;
        private final String service;

        public PortResult(int port, boolean isOpen, String service){
            this.port = port;
            this.isOpen = isOpen;
            this.service = service;
        }

        public int getPort(){
            return port;
        }

        public boolean isOpen(){
            return isOpen;
        }

        public String getService(){
            return service;
        }
    }
}