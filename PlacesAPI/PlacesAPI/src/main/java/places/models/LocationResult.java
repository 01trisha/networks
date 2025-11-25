package places.models;

import java.util.List;

public class LocationResult {
    private final List<Location> locations;

    public LocationResult(List<Location> locations){
        this.locations = locations;
    }

    public List<Location> getLocations(){
        return locations;
    }
}
