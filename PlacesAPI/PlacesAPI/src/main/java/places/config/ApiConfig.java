package places.config;

public class ApiConfig {
    public static final String LOCATION_API_URL = Config.get("LOCATION_API_URL");
    public static final String LOCATION_API_KEY = Config.get("LOCATION_API_KEY");

    public static final String WEATHER_API_URL = Config.get("WEATHER_API_URL");
    public static final String WEATHER_API_KEY = Config.get("WEATHER_API_KEY");

    public static final String PLACES_APU_URL = Config.get("PLACES_API_URL");

    public static String buldLocationUrl(String location){
        return LOCATION_API_URL + "?q=" + location.replace(" ", "+") + "&locale=ru&key=" + LOCATION_API_KEY + "&limit=5";
    }

    public static String buildWeatherUrl(double lat, double lon){
        return WEATHER_API_URL + "?lat=" + lat + "&lon=" + lon + "&appid=" + WEATHER_API_KEY + "&units=metric&lang=ru";
    }

    public static String buildWikipediaGeosearchUrl(double lat, double lon, int radius){
        return PLACES_APU_URL + "?action=query&list=geosearch&gscoord=" + lat + "%7C" + lon + "&gsradius=" + radius + "&gslimit=50&format=json";
    }

    public static String buildWikipediaPageDetailsUrl(long pageId){
        return PLACES_APU_URL + "?action=query&pageids=" + pageId + "&prop=extracts%7Cpageimages%7Cinfo&exintro=1&explaintext=1&piprop=thumbnail&pithumbsize=500&inprop=url&format=json";
    }
}
