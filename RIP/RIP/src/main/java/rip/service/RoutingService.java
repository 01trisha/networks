package rip.service;

import rip.model.Router;
import rip.model.RoutingTableEntry;
import java.util.*;
import java.util.concurrent.*;

// Сервис для реализации логики RIP
public class RoutingService {
    private static final int UPDATE_INTERVAL = 5; // seconds
    private static final int INFINITY = 16;

    public void startPeriodicUpdates(Router router) {
        router.getExecutor().scheduleAtFixedRate(() -> {
            sendUpdates(router);
        }, UPDATE_INTERVAL, UPDATE_INTERVAL, TimeUnit.SECONDS);
    }

    public void addDirectRoute(Router router, Router neighbor) {
        router.getRoutingTable().put(neighbor.getName(),
                new RoutingTableEntry(neighbor.getName(), 1, neighbor));
    }

    public void sendUpdates(Router router) {
        Map<String, Integer> update = prepareUpdate(router);
        for (Router neighbor : router.getNeighbors()) {
            Map<String, Integer> poisonedUpdate = applyPoisonReverse(router, neighbor, update);
            neighbor.receiveUpdate(router, poisonedUpdate);
        }
    }

    private Map<String, Integer> prepareUpdate(Router router) {
        Map<String, Integer> update = new HashMap<>();
        for (RoutingTableEntry entry : router.getRoutingTable().values()) {
            // Отправляем все маршруты, но для Poison Reverse будем менять метрику позже
            update.put(entry.getDestination(), entry.getMetric());
        }
        return update;
    }

    private Map<String, Integer> applyPoisonReverse(Router router, Router neighbor, Map<String, Integer> update) {
        Map<String, Integer> poisonedUpdate = new HashMap<>();
        for (Map.Entry<String, Integer> entry : update.entrySet()) {
            RoutingTableEntry tableEntry = router.getRoutingTable().get(entry.getKey());
            // Poison Reverse: если nextHop для этого маршрута - наш сосед, отправляем INFINITY
            if (tableEntry != null && tableEntry.getNextHop() == neighbor) {
                poisonedUpdate.put(entry.getKey(), INFINITY);
            } else {
                poisonedUpdate.put(entry.getKey(), entry.getValue());
            }
        }
        return poisonedUpdate;
    }

    public void receiveUpdate(Router router, Router sender, Map<String, Integer> receivedTable) {
        for (Map.Entry<String, Integer> entry : receivedTable.entrySet()) {
            processRoute(router, sender, entry.getKey(), entry.getValue() + 1);
        }
    }

    private void processRoute(Router router, Router sender, String destination, int newMetric) {
        if (destination.equals(router.getName())) return;

        RoutingTableEntry currentEntry = router.getRoutingTable().get(destination);

        if (newMetric >= INFINITY) newMetric = INFINITY;

        if (currentEntry == null) {
            if (newMetric < INFINITY) {
                router.getRoutingTable().put(destination,
                        new RoutingTableEntry(destination, newMetric, sender));
            }
        } else {
            if (newMetric < currentEntry.getMetric()) {
                currentEntry.setMetric(newMetric);
                currentEntry.setNextHop(sender);
            } else if (currentEntry.getNextHop() == sender && newMetric != currentEntry.getMetric()) {
                currentEntry.setMetric(newMetric);
                if (newMetric >= INFINITY) {
                    currentEntry.setNextHop(null);
                }
            }
        }
    }
}