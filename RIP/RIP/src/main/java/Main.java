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

        network.connectRouters("R1", "R2");
        network.connectRouters("R2", "R3");
        network.connectRouters("R3", "R4");
        network.connectRouters("R1", "R4");

        System.out.println("Initial routing tables:");
        network.printAllRoutingTables();

        System.out.println("Waiting for convergence (15 seconds)...");
        TimeUnit.SECONDS.sleep(15);

        System.out.println("Routing tables after convergence:");
        network.printAllRoutingTables();

        System.out.println("Disconnecting R1-R4...");
        network.disconnectRouters("R1", "R4");

        System.out.println("Waiting after disconnection (15 seconds)...");
        TimeUnit.SECONDS.sleep(15);

        System.out.println("Final routing tables:");
        network.printAllRoutingTables();

        network.shutdownAll();
    }
}