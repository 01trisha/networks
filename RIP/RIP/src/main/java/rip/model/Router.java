package rip.model;



import rip.service.RoutingService;
import java.util.*;
import java.util.concurrent.*;

public class Router {
    private final String name;
    private final Map<String, RoutingTableEntry> routingTable = new ConcurrentHashMap<>();
    private final List<Router> neighbors = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final RoutingService routingService;

    public Router(String name, RoutingService routingService) {
        this.name = name;
        this.routingService = routingService;
        routingTable.put(name, new RoutingTableEntry(name, 0, null));
    }

    public void start() {
        routingService.startPeriodicUpdates(this);
    }

    public void addNeighbor(Router neighbor) {
        if (!neighbors.contains(neighbor)) {
            neighbors.add(neighbor);
            routingService.addDirectRoute(this, neighbor);
        }
    }

    public void receiveUpdate(Router sender, Map<String, Integer> receivedTable) {
        routingService.receiveUpdate(this, sender, receivedTable);
    }

    public String getName() { return name; }
    public Map<String, RoutingTableEntry> getRoutingTable() { return routingTable; }
    public List<Router> getNeighbors() { return neighbors; }
    public ScheduledExecutorService getExecutor() { return executor; }

    public void shutdown() {
        executor.shutdown();
    }
}