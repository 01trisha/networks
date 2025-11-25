package places.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {
    private static final Properties properties = new Properties();

    static{
        try(InputStream input = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            if(input == null){
                System.out.println("Cannot find properties file");
            }
            properties.load(input);
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    public static String get(String key){
        return properties.getProperty(key);
    }
}
