package rip.model;

public class RoutingTableEntry {
    private final String destination;
    private int metric;
    private Router nextHop;

    public RoutingTableEntry(String destination, int metric, Router nextHop) {
        this.destination = destination;
        this.metric = metric;
        this.nextHop = nextHop;
    }

    public String getDestination() { return destination; }
    public int getMetric() { return metric; }
    public void setMetric(int metric) { this.metric = metric; }
    public Router getNextHop() { return nextHop; }
    public void setNextHop(Router nextHop) { this.nextHop = nextHop; }
}