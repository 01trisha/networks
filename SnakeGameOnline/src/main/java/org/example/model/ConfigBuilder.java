package org.example.model;

import me.ippolitov.fit.snakes.SnakesProto.GameConfig;

//класс для создания различных конфигураций игрового поля
public class ConfigBuilder {
    
    //создаёт стандартную конфигурацию игры со средними параметрами
    public static GameConfig createDefault() {
        return GameConfig.newBuilder()
                .setWidth(40) //ширина поля 40 клеток
                .setHeight(30) //высота поля 30 клеток
                .setFoodStatic(1) //одна статичная еда на поле
                .setStateDelayMs(1000) //обновление состояния каждую секунду
                .build();
    }
    
    //создаёт маленькую конфигурацию для быстрой игры
    public static GameConfig createSmall() {
        return GameConfig.newBuilder()
                .setWidth(20) //уменьшенная ширина поля
                .setHeight(15) //уменьшенная высота поля
                .setFoodStatic(1) //одна еда
                .setStateDelayMs(500) //быстрое обновление каждые 0.5 секунды
                .build();
    }
    
    //создаёт большую конфигурацию для игры с большим количеством игроков
    public static GameConfig createLarge() {
        return GameConfig.newBuilder()
                .setWidth(60) //увеличенная ширина поля
                .setHeight(40) //увеличенная высота поля
                .setFoodStatic(2) //две единицы еды для большего поля
                .setStateDelayMs(1000) //стандартное обновление
                .build();
    }
    
    //создаёт пользовательскую конфигурацию с проверкой корректности параметров
    public static GameConfig createCustom(int width, int height, int foodStatic, int stateDelayMs) {
        //ограничивает ширину в диапазоне от 10 до 100
        width = Math.max(10, Math.min(100, width));
        //ограничивает высоту в диапазоне от 10 до 100
        height = Math.max(10, Math.min(100, height));
        //ограничивает количество еды в диапазоне от 0 до 100
        foodStatic = Math.max(0, Math.min(100, foodStatic));
        //ограничивает задержку обновления в диапазоне от 100 до 3000 миллисекунд
        stateDelayMs = Math.max(100, Math.min(3000, stateDelayMs));
        
        return GameConfig.newBuilder()
                .setWidth(width)
                .setHeight(height)
                .setFoodStatic(foodStatic)
                .setStateDelayMs(stateDelayMs)
                .build();
    }
}
