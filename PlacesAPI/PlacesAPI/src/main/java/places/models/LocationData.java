package places.models;

import java.util.List;

public class LocationData {
    private final Location location;
    private final Weather weather;
    private final List<Place> places;
    private final List<PlaceDetails> placesDetails;

    public LocationData(Location location, Weather weather, List<Place> places, List<PlaceDetails> placesDetails) {
        this.location = location;
        this.weather = weather;
        this.places = places;
        this.placesDetails = placesDetails;
    }

    public Location getLocation() {
        return location;
    }

    public Weather getWeather() {
        return weather;
    }

    public List<Place> getPlaces() {
        return places;
    }

    public List<PlaceDetails> getPlacesDetails() {
        return placesDetails;
    }
}
