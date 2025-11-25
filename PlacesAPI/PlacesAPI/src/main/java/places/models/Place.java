package places.models;

public class Place {
    private long pageId;
    private String name;
    private Double dist;

    public Place(long pageId, String name, Double dist) {
        this.pageId = pageId;
        this.name = name;
        this.dist = dist;
    }

    public long getPageId() {
        return pageId;
    }

    public String getName() {
        return name;
    }

    public Double getDist() {
        return dist;
    }
}
