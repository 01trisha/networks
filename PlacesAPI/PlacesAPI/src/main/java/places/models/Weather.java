package places.models;

public class Weather {
    private double temperature;
    private String description;
    private double humidity;
    private double windSpeed;

    public Weather(double temperature, String description, double humidity, double windSpeed) {
        this.temperature = temperature;
        this.description = description;
        this.humidity = humidity;
        this.windSpeed = windSpeed;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getDescription() {
        return description;
    }

    public double getHumidity() {
        return humidity;
    }

    public double getWindSpeed() {
        return windSpeed;
    }
}
