package org.example.model;

import me.ippolitov.fit.snakes.SnakesProto.*;
import me.ippolitov.fit.snakes.SnakesProto.GameState.Coord;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//представляет игровое поле и управляет всеми объектами на нём
public class GameField {
    private final int width; //ширина игрового поля
    private final int height; //высота игрового поля
    private final Map<Integer, Snake> snakes; //коллекция всех змей на поле по id игрока
    private final Set<Coord> foods; //множество координат еды на поле
    private final Map<Integer, PlayerInfo> players; //информация о всех игроках по id
    private int stateOrder; //порядковый номер текущего состояния игры
    
    //хранит информацию об игроке в игре
    public static class PlayerInfo {
        public String name; //имя игрока
        public int id; //уникальный идентификатор игрока
        public NodeRole role; //роль в сети (master/normal/viewer)
        public int score; //текущий счёт игрока
        public String ipAddress; //ip адрес игрока
        public int port; //порт для сетевого соединения
        
        //создаёт информацию об игроке с начальным счётом 0
        public PlayerInfo(String name, int id, NodeRole role) {
            this.name = name;
            this.id = id;
            this.role = role;
            this.score = 0;
        }
    }
    
    //представляет змею на игровом поле
    public static class Snake {
        public int playerId; //id игрока которому принадлежит змея
        public List<Coord> points; //список координат сегментов змеи
        public GameState.Snake.SnakeState state; //состояние змеи (alive/dead)
        public Direction headDirection; //направление движения головы змеи
        
        //создаёт новую живую змею с заданными координатами и направлением
        public Snake(int playerId, List<Coord> points, Direction headDirection) {
            this.playerId = playerId;
            this.points = new ArrayList<>(points);
            this.state = GameState.Snake.SnakeState.ALIVE;
            this.headDirection = headDirection;
        }
        
        //возвращает все клетки которые занимает змея с учётом тороидального поля
        public List<Coord> getAllCells(int width, int height) {
            List<Coord> cells = new ArrayList<>();
            if (points.isEmpty()) return cells;
            
            //добавляет голову змеи
            int x = points.get(0).getX();
            int y = points.get(0).getY();
            cells.add(Coord.newBuilder().setX(x).setY(y).build());
            
            //проходит по всем сегментам змеи начиная со второго
            for (int i = 1; i < points.size(); i++) {
                Coord offset = points.get(i);
                int dx = offset.getX();
                int dy = offset.getY();
                
                //вычисляет количество шагов в этом сегменте
                int steps = Math.max(Math.abs(dx), Math.abs(dy));
                int stepX = dx == 0 ? 0 : dx / Math.abs(dx);
                int stepY = dy == 0 ? 0 : dy / Math.abs(dy);
                
                //добавляет каждую клетку сегмента с учётом тороидального поля
                for (int j = 1; j <= steps; j++) {
                    x = (x + stepX + width) % width;
                    y = (y + stepY + height) % height;
                    cells.add(Coord.newBuilder().setX(x).setY(y).build());
                }
            }
            return cells;
        }
    }
    
    //создаёт пустое игровое поле заданного размера
    public GameField(int width, int height) {
        this.width = width;
        this.height = height;
        this.snakes = new ConcurrentHashMap<>();
        this.foods = ConcurrentHashMap.newKeySet();
        this.players = new ConcurrentHashMap<>();
        this.stateOrder = 0;
    }
    
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Map<Integer, Snake> getSnakes() { return snakes; }
    public Set<Coord> getFoods() { return foods; }
    public Map<Integer, PlayerInfo> getPlayers() { return players; }
    public int getStateOrder() { return stateOrder; }
    
    //увеличивает счётчик порядка состояния на единицу
    public void incrementStateOrder() { stateOrder++; }
    
    //добавляет змею на поле для конкретного игрока
    public void addSnake(int playerId, Snake snake) {
        snakes.put(playerId, snake);
    }
    
    //удаляет змею игрока с поля
    public void removeSnake(int playerId) {
        snakes.remove(playerId);
    }
    
    //добавляет еду на указанную координату
    public void addFood(Coord food) {
        foods.add(food);
    }
    
    //удаляет еду с указанной координаты
    public void removeFood(Coord food) {
        foods.remove(food);
    }
    
    //добавляет игрока в игру
    public void addPlayer(PlayerInfo player) {
        players.put(player.id, player);
    }
    
    //удаляет игрока из игры
    public void removePlayer(int playerId) {
        players.remove(playerId);
    }
    
    //возвращает информацию об игроке по его id
    public PlayerInfo getPlayer(int playerId) {
        return players.get(playerId);
    }
    
    //проверяет занята ли клетка какой-либо змеёй
    public boolean isCellOccupied(int x, int y) {
        Coord coord = Coord.newBuilder().setX(x).setY(y).build();
        
        //проверяет все змеи на поле
        for (Snake snake : snakes.values()) {
            for (Coord cell : snake.getAllCells(width, height)) {
                if (cell.getX() == x && cell.getY() == y) {
                    return true;
                }
            }
        }
        return false;
    }
    
    //проверяет можно ли разместить новую змею в области 5х5 клеток с центром в указанной точке
    public boolean canPlaceSnake(int centerX, int centerY) {
        //проходит по квадрату 5х5 клеток
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int x = (centerX + dx + width) % width;
                int y = (centerY + dy + height) % height;
                if (isCellOccupied(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
    
    //нормализует координаты для тороидального поля (зацикливает границы)
    public Coord normalizeCoord(int x, int y) {
        return Coord.newBuilder()
                .setX((x + width) % width)
                .setY((y + height) % height)
                .build();
    }
}
