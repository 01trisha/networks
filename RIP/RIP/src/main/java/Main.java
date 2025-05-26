import rip.model.Network;
import rip.model.Router;
import rip.service.RoutingService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        RoutingService routingService = new RoutingService();
        Network network = new Network(routingService);

        network.addRouter("R1");
        network.addRouter("R2");
        network.addRouter("R3");
        network.addRouter("R4");
        network.addRouter("R5");

        network.connectRouters("R1", "R2");
        network.connectRouters("R2", "R3");
        network.connectRouters("R3", "R4");
        network.connectRouters("R1", "R4");
        network.connectRouters("R4", "R5");
        network.connectRouters("R2", "R4");

        System.out.println("начальная таблица:");
        network.printAllRoutingTables();

        System.out.println("ждем 20 секунд");
        TimeUnit.SECONDS.sleep(20);

        System.out.println("итоговая таблица после роутинга:");
        network.printAllRoutingTables();

//        System.out.println("отключаем связь R1 - R4");
//        network.disconnectRouters("R1", "R4");
//
//        System.out.println("ждем 20 секунд");
//        TimeUnit.SECONDS.sleep(20);
//
//        System.out.println("финальная таблица:");
//        network.printAllRoutingTables();

        network.shutdownAll();

//        RoutingService routingService1 = new RoutingService();
//        Network network2 = new Network(routingService);
//
//        network2.addRouter("R1");
//        network2.addRouter("R2");
//        network2.addRouter("R3");
//        network2.addRouter("R4");
//        network2.addRouter("R5");
//
//        network2.connectRouters("R1", "R2");
//        network2.connectRouters("R2", "R3");
//        network2.connectRouters("R3", "R4");
//        network.connectRouters("R4", "R5");
//        network2.connectRouters("R2", "R4");
//
    }
}