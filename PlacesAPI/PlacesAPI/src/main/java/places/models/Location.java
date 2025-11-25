package places.models;

public class Location {
    private String name;
    private String country;
    private String city;
    private double lon;
    private double lat;

    public Location(String name, String country, String city, double lat, double lon){
        this.name = name;
        this.country = country;
        this.city = city;
        this.lat = lat;
        this.lon = lon;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getCity() {
        return city;
    }

    public double getLon() {
        return lon;
    }

    public double getLat() {
        return lat;
    }
}
