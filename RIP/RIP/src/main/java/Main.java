import rip.model.Network;
import rip.service.RoutingService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        RoutingService routingService = new RoutingService();
        Network network = new Network(routingService);

        // Создаем роутеры
        network.addRouter("R1");
        network.addRouter("R2");
        network.addRouter("R3");
        network.addRouter("R4");

        // Настраиваем соединения
        network.connectRouters("R1", "R2");
        network.connectRouters("R2", "R3");
        network.connectRouters("R3", "R4");

        // Выводим начальные таблицы
        System.out.println("Initial routing tables:");
        network.printAllRoutingTables();

        // Ждем обновлений
        System.out.println("Waiting for routing updates...");
        TimeUnit.SECONDS.sleep(15);

        // Выводим обновленные таблицы
        System.out.println("Updated routing tables:");
        network.printAllRoutingTables();

        // Добавляем новое соединение
        System.out.println("Adding R1-R4 connection...");
        network.connectRouters("R1", "R4");

        // Ждем обновлений
        TimeUnit.SECONDS.sleep(15);

        // Выводим финальные таблицы
        System.out.println("Final routing tables:");
        network.printAllRoutingTables();

        network.shutdownAll();
    }
}