package places.models;

public class PlaceDetails {
    private long pageId;
    private String name;
    private String description;
    private String wikipediaUrl;
    private String image;

    public PlaceDetails(long pageId, String name, String description, String wikipediaUrl, String image) {
        this.pageId = pageId;
        this.name = name;
        this.description = description;
        this.wikipediaUrl = wikipediaUrl;
        this.image = image;
    }

    public long getPageId() {
        return pageId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getWikipediaUrl() {
        return wikipediaUrl;
    }

    public String getImage() {
        return image;
    }
}
