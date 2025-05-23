package rip.model;

import rip.service.RoutingService;

import java.util.ArrayList;
import java.util.List;

//cеть маршрутизаторов
public class Network {
    private final List<Router> routers = new ArrayList<>();
    private final RoutingService routingService;

    public Network(RoutingService routingService) {
        this.routingService = routingService;
    }

    public Router addRouter(String name) {
        Router router = new Router(name, routingService);
        routers.add(router);
        router.start();
        return router;
    }

    public void connectRouters(String name1, String name2) {
        Router r1 = findRouter(name1);
        Router r2 = findRouter(name2);
        if (r1 != null && r2 != null) {
            r1.addNeighbor(r2);
            r2.addNeighbor(r1);
        }
    }

    private Router findRouter(String name) {
        return routers.stream()
                .filter(r -> r.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void printAllRoutingTables() {
        for (Router router : routers) {
            System.out.println("таблица маршрутизации для " + router.getName() + ":");
            System.out.println("роутер \tметрица\tследующий прыжок");

            for (RoutingTableEntry entry : router.getRoutingTable().values()) {
                String nextHopName = "-";
                if (entry.getNextHop() != null) {
                    nextHopName = entry.getNextHop().getName();
                }

                System.out.printf("%-10s %-5d %s%n",
                        entry.getDestination(),
                        entry.getMetric(),
                        nextHopName);
            }
            System.out.println();
        }
    }

    public void disconnectRouters(String name1, String name2) {
        Router r1 = findRouter(name1);
        Router r2 = findRouter(name2);
        if (r1 != null && r2 != null) {
            r1.getNeighbors().remove(r2);
            r2.getNeighbors().remove(r1);

            routingService.handleDisconnection(r1, r2);
            routingService.handleDisconnection(r2, r1);
        }
    }

    public void shutdownAll() {
        routers.forEach(Router::shutdown);
    }
}