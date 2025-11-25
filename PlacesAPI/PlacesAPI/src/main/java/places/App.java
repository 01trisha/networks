package places;

import places.models.*;
import places.services.LocationService;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class App {
    private final LocationService locationService;
    private final Scanner scanner;

    public App() {
        this.locationService = new LocationService();
        this.scanner = new Scanner(System.in);
    }

    public void start(){
        System.out.println("Введите название места: ");
        String name = scanner.nextLine();

        CompletableFuture<LocationResult> locationFuture = locationService.searchLocations(name);

        LocationResult locationResult = locationFuture.join();

        List<Location> locations = locationResult.getLocations();

        if(locations.isEmpty()){
            System.out.println("Места не найдены");
            return;
        }

        System.out.println("\nНайденные места: ");
        for(int i = 0; i < locations.size(); i++){
            Location loc = locations.get(i);
            System.out.printf("%d. %s, %s, %s, (%.4f, %.4f)%n", i+1, loc.getName(), loc.getCity(), loc.getCountry(), loc.getLat(), loc.getLon());
        }
        int choice;
        while(true) {
            System.out.print("Выберите место 1-" + locations.size() + "): ");
            choice = scanner.nextInt() - 1;

            scanner.nextLine();
            if (choice < 0 || choice >= locations.size()) {
                System.out.println("Неверный выбор, попробуйте еще раз");
            }else{
                break;
            }
        }

        Location selectedLocation = locations.get(choice);

        System.out.println("\nОбрабатываем выбор");
        CompletableFuture<LocationData> locationDataFuture = locationService.getLocationData(selectedLocation);
        LocationData locationData = locationDataFuture.join();
        displayResult(locationData);
    }

    private void displayResult(LocationData data){
        System.out.println("Результат: \n");
        System.out.println("Место: " + data.getLocation().getName());
        System.out.println("Координаты: " + data.getLocation().getLat() + ", " + data.getLocation().getLon());

        Weather weather = data.getWeather();
        System.out.println("ПОГОДА: \n");
        System.out.printf("Температура: %.1f°C%n", weather.getTemperature());
        System.out.println("Описание: " + weather.getDescription());
        System.out.printf("Влажность: %.1f%%%n", weather.getHumidity());
        System.out.printf("Скорость ветра: %.1f м/с%n", weather.getWindSpeed());

        System.out.println("\nИНТЕРЕСНЫЕ МЕСТА:");
        List<Place> places = data.getPlaces();
        List<PlaceDetails> details = data.getPlacesDetails();

        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            PlaceDetails detail = i < details.size() ? details.get(i) : null;

            System.out.printf("%d. %s%n", i + 1, place.getName());
            if (detail != null && !detail.getDescription().isEmpty()) {
                String description = detail.getDescription();
                System.out.println("   Описание: " +
                        (description.length() > 200 ?
                                description.substring(0, 200) + "..." :
                                description));
            }
            System.out.println();
        }
    }
}