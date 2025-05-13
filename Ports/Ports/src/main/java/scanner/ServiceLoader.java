package scanner;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class ServiceLoader{
    private final Map<Integer, String> servicesMap = new HashMap<>();

    public ServiceLoader(){
        loadServices();
    }

    private void loadServices(){
        File servicesFile = new File("/etc/services");

        try (BufferedReader reader = new BufferedReader(new FileReader(servicesFile))){
            String line;
            while ((line = reader.readLine()) != null){
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                if (parts.length >= 2){
                    String[] portProto = parts[1].split("/");
                    if (portProto.length == 2){
                        try{
                            int port = Integer.parseInt(portProto[0]);
                            servicesMap.put(port, parts[0]);
                        } catch (NumberFormatException ignored){}
                    }
                }
            }
        } catch (IOException e){
            System.err.println("Ошибка загрузки служб: " + e.getMessage());
        }
    }

    public String getServiceName(int port){
        return servicesMap.getOrDefault(port, "unknown");
    }

}