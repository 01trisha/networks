package scanner;

import static java.lang.Thread.sleep;

public class SingleScanner{
    private final ServiceLoader serviceLoader;

    public SingleScanner(ServiceLoader serviceLoader){
        this.serviceLoader = serviceLoader;
    }

    public void scan(int port){
       try{
           boolean isOpen = CheckPort.isPortOpen("localhost", port);
           String service = serviceLoader.getServiceName(port);
           System.out.printf("Порт %d: %s, служба: %s%n", port, isOpen ? "открыт" : "закрыт", service);
           sleep(500);
       }catch (Exception e){
           System.out.println("Недопустимый номер порта");
       }
    }


}
