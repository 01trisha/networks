package rip.model;

import rip.service.RoutingService;

import java.util.ArrayList;
import java.util.List;

// Сеть маршрутизаторов
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
        routers.forEach(router -> {
            System.out.println("Routing table for " + router.getName() + ":");
            router.getRoutingTable().values().forEach(entry -> {
                System.out.printf("%-10s %-5d %s%n",
                        entry.getDestination(),
                        entry.getMetric(),
                        entry.getNextHop() == null ? "-" : entry.getNextHop().getName());
            });
            System.out.println();
        });
    }

    public void shutdownAll() {
        routers.forEach(Router::shutdown);
    }
}