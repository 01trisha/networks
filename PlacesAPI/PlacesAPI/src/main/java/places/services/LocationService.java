package places.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import places.config.ApiConfig;
import places.models.*;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LocationService extends ApiService{

    public CompletableFuture<LocationResult> searchLocations(String qwery){
        String url = ApiConfig.buldLocationUrl(qwery);

        return makeAsyncRequest(url).thenApply(response -> {
            try{
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                JsonNode hits = root.path("hits");

                List<Location> locations = new ArrayList<>();
                for(JsonNode hit: hits){
                    String name = hit.path("name").asText();
                    String country = hit.path("country").asText();
                    String city = hit.path("city").asText();
                    double lat = hit.path("point").path("lat").asDouble();
                    double lon = hit.path("point").path("lng").asDouble();

                    locations.add(new Location(name, country, city, lat, lon));
                }

                return new LocationResult(locations);
            }catch (Exception e){
                throw new RuntimeException("Cannot parsing locations:" + e);
            }
        });
    }

    public CompletableFuture<LocationData> getLocationData(Location location){
        CompletableFuture<Weather> weatherFuture = getWeather(location);
        CompletableFuture<List<Place>> placesFuture = getPlaces(location);

        CompletableFuture<List<PlaceDetails>> placesDetailsFuture = placesFuture.thenCompose(this::getPlacesDetails);

        return placesFuture.thenCombine(weatherFuture, (places, weather) -> Map.entry(places, weather))
                .thenCombine(placesDetailsFuture, (entry, placesDetails) ->
                        new LocationData(location, entry.getValue(), entry.getKey(), placesDetails));
    }

    private CompletableFuture<Weather> getWeather(Location location){
        String url = ApiConfig.buildWeatherUrl(location.getLat(), location.getLon());

        return makeAsyncRequest(url).thenApply(response -> {
            try{
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                JsonNode main = root.path("main");
                JsonNode weather = root.path("weather").get(0);
                JsonNode wind = root.path("wind");

                double temp = main.path("temp").asDouble();
                String desc = weather.path("description").asText();
                double humidity = main.path("humidity").asDouble();
                double windSpeed = wind.path("speed").asDouble();

                return new Weather(temp, desc, humidity, windSpeed);
            }catch (Exception e){
                throw new RuntimeException("Cannot parsing weather: " + e);
            }
        });
    }

    private CompletableFuture<List<Place>> getPlaces(Location location){
        String url = ApiConfig.buildWikipediaGeosearchUrl(location.getLat(), location.getLon(), 1000);

        return makeAsyncRequest(url).thenApply(response -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                JsonNode geosearch = root.path("query").path("geosearch");
                List<Place> places = new ArrayList<>();

                for (JsonNode placeNode : geosearch) {
                    long pageId = placeNode.path("pageid").asLong();
                    String title = placeNode.path("title").asText();
                    double dist = placeNode.path("dist").asDouble();

                    if (!title.isEmpty() && pageId != 0) {
                        places.add(new Place(pageId, title, dist));
                    }
                }
                return places;
            }catch (Exception e){
                throw new RuntimeException("Cannot parsing places: " + e);
            }
        });
    }

    private CompletableFuture<List<PlaceDetails>> getPlacesDetails(List<Place> places){
        List<CompletableFuture<PlaceDetails>> detailsFutures = new ArrayList<>();

        for(Place place : places){
            CompletableFuture<PlaceDetails> detailFuture = getPlacesDetail(place.getPageId());
            detailsFutures.add(detailFuture);
        }

        return CompletableFuture.allOf(detailsFutures.toArray(new CompletableFuture[0])).thenApply(a -> {
            List<PlaceDetails> details = new ArrayList<>();
            for (CompletableFuture<PlaceDetails> future : detailsFutures){
                try{
                    details.add(future.get());
                }catch (Exception e){

                }
            }
            return details;
        });
    }

    private CompletableFuture<PlaceDetails> getPlacesDetail(long pageId){
        String url = ApiConfig.buildWikipediaPageDetailsUrl(pageId);

        return makeAsyncRequest(url).thenApply(response -> {
            try{
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                JsonNode pages = root.path("query").path("pages");
                JsonNode page = pages.path(String.valueOf(pageId));

                String title = page.path("title").asText();
                String extract = page.path("extract").asText();
                String wikipediaUrl = page.path("fullurl").asText();
                String imageUrl = page.path("thumbnail").path("source").asText();

                return new PlaceDetails(pageId, title, extract, wikipediaUrl, imageUrl);
            }catch (Exception e){
                return new PlaceDetails(pageId, "Unknown", "No description", "", "");
            }
        });
    }
}
