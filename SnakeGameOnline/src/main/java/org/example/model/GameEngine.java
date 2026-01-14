package org.example.model;

import me.ippolitov.fit.snakes.SnakesProto.*;
import me.ippolitov.fit.snakes.SnakesProto.GameState.Coord;
import org.example.model.GameField.Snake;
import org.example.model.GameField.PlayerInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//основной движок игры, управляющий логикой игрового процесса
public class GameEngine {
    private final GameField field; //игровое поле со всеми объектами
    private final GameConfig config; //конфигурация игры
    private final Random random = new Random(); //генератор случайных чисел для размещения объектов
    private final Map<Integer, Direction> pendingMoves = new ConcurrentHashMap<>(); //очередь ожидающих ходов игроков
    
    //создаёт новый движок игры с заданной конфигурацией
    public GameEngine(GameConfig config) {
        this.config = config;
        this.field = new GameField(config.getWidth(), config.getHeight());
    }
    
    public GameField getField() {
        return field;
    }
    
    public GameConfig getConfig() {
        return config;
    }
    
    //регистрирует направление движения для игрока на следующий ход
    public void setPlayerMove(int playerId, Direction direction) {
        pendingMoves.put(playerId, direction);
    }
    
    //выполняет один игровой ход обновляя состояние всей игры
    public void nextTurn() {
        applyMoves(); //применяет команды движения от игроков
        moveSnakes(); //перемещает все змеи
        checkCollisions(); //проверяет столкновения
        removeDeadSnakes(); //удаляет мёртвые змеи
        spawnFood(); //создаёт новую еду
        field.incrementStateOrder(); //увеличивает счётчик состояния
    }
    
    //применяет зарегистрированные движения к змеям игроков
    private void applyMoves() {
        for (Map.Entry<Integer, Direction> entry : pendingMoves.entrySet()) {
            int playerId = entry.getKey();
            Direction newDirection = entry.getValue();
            
            Snake snake = field.getSnakes().get(playerId);
            if (snake == null || snake.state != GameState.Snake.SnakeState.ALIVE) {
                continue;
            }
            
            //запрещает поворот в противоположную сторону
            if (!isOppositeDirection(snake.headDirection, newDirection)) {
                snake.headDirection = newDirection;
            }
        }
        pendingMoves.clear();
    }
    
    //перемещает все змеи на одну клетку в направлении их движения
    private void moveSnakes() {
        //кеш позиций еды для оптимизации проверок
        Map<String, Boolean> foodAtPosition = new HashMap<>();
        
        //первый проход определяет где находится еда
        for (Snake snake : field.getSnakes().values()) {
            if (snake.state != GameState.Snake.SnakeState.ALIVE && 
                snake.state != GameState.Snake.SnakeState.ZOMBIE) {
                continue;
            }
            
            List<Coord> cells = snake.getAllCells(field.getWidth(), field.getHeight());
            if (cells.isEmpty()) continue;
            
            Coord head = cells.get(0);
            int newX = head.getX();
            int newY = head.getY();
            
            //вычисляет новую позицию головы змеи
            switch (snake.headDirection) {
                case UP: newY--; break;
                case DOWN: newY++; break;
                case LEFT: newX--; break;
                case RIGHT: newX++; break;
            }
            
            //нормализует координаты для тороидального поля
            newX = (newX + field.getWidth()) % field.getWidth();
            newY = (newY + field.getHeight()) % field.getHeight();
            
            String posKey = newX + "," + newY;
            
            //проверяет есть ли еда на новой позиции
            if (!foodAtPosition.containsKey(posKey)) {
                boolean hasFood = false;
                for (Coord food : field.getFoods()) {
                    if (food.getX() == newX && food.getY() == newY) {
                        hasFood = true;
                        break;
                    }
                }
                foodAtPosition.put(posKey, hasFood);
            }
        }
        
        //второй проход выполняет фактическое перемещение змей
        for (Snake snake : field.getSnakes().values()) {
            if (snake.state != GameState.Snake.SnakeState.ALIVE && 
                snake.state != GameState.Snake.SnakeState.ZOMBIE) {
                continue;
            }
            
            List<Coord> cells = snake.getAllCells(field.getWidth(), field.getHeight());
            if (cells.isEmpty()) continue;
            
            Coord head = cells.get(0);
            int newX = head.getX();
            int newY = head.getY();
            
            //вычисляет новую позицию головы
            switch (snake.headDirection) {
                case UP: newY--; break;
                case DOWN: newY++; break;
                case LEFT: newX--; break;
                case RIGHT: newX++; break;
            }
            
            newX = (newX + field.getWidth()) % field.getWidth();
            newY = (newY + field.getHeight()) % field.getHeight();
            
            Coord newHead = Coord.newBuilder().setX(newX).setY(newY).build();
            String posKey = newX + "," + newY;
            boolean ateFood = foodAtPosition.getOrDefault(posKey, false);
            
            //если змея съела еду удаляет её и увеличивает счёт
            if (ateFood) {
                if (foodAtPosition.get(posKey)) {
                    Iterator<Coord> foodIter = field.getFoods().iterator();
                    while (foodIter.hasNext()) {
                        Coord food = foodIter.next();
                        if (food.getX() == newX && food.getY() == newY) {
                            foodIter.remove();
                            break;
                        }
                    }
                    foodAtPosition.put(posKey, false);
                }
                
                PlayerInfo player = field.getPlayer(snake.playerId);
                if (player != null) {
                    player.score++;
                }
            }
            
            //создаёт новый список клеток змеи с новой головой
            List<Coord> newCells = new ArrayList<>();
            newCells.add(newHead);
            newCells.addAll(cells);
            
            //если еда не съедена удаляет хвост змеи
            if (!ateFood) {
                newCells.remove(newCells.size() - 1);
            }
            
            //компактифицирует представление змеи
            snake.points = compactSnake(newCells);
        }
    }
    
    //преобразует список всех клеток змеи в компактное представление со смещениями
    private List<Coord> compactSnake(List<Coord> cells) {
        List<Coord> points = new ArrayList<>();
        if (cells.isEmpty()) return points;
        
        //добавляет голову змеи как абсолютную координату
        points.add(cells.get(0));
        
        if (cells.size() == 1) return points;
        
        int prevX = cells.get(0).getX();
        int prevY = cells.get(0).getY();
        int currX = cells.get(1).getX();
        int currY = cells.get(1).getY();
        
        //вычисляет начальное смещение с учётом тороидальности
        int dx = currX - prevX;
        int dy = currY - prevY;
        
        if (dx > field.getWidth() / 2) dx -= field.getWidth();
        if (dx < -field.getWidth() / 2) dx += field.getWidth();
        if (dy > field.getHeight() / 2) dy -= field.getHeight();
        if (dy < -field.getHeight() / 2) dy += field.getHeight();
        
        int segX = dx;
        int segY = dy;
        
        //объединяет последовательные смещения в одном направлении
        for (int i = 2; i < cells.size(); i++) {
            prevX = currX;
            prevY = currY;
            currX = cells.get(i).getX();
            currY = cells.get(i).getY();
            
            dx = currX - prevX;
            dy = currY - prevY;
            
            //нормализует смещение для тороидального поля
            if (dx > field.getWidth() / 2) dx -= field.getWidth();
            if (dx < -field.getWidth() / 2) dx += field.getWidth();
            if (dy > field.getHeight() / 2) dy -= field.getHeight();
            if (dy < -field.getHeight() / 2) dy += field.getHeight();
            
            //проверяет идёт ли змея в том же направлении
            boolean sameDir = false;
            if (segX != 0) {
                sameDir = (dx == Integer.signum(segX) && dy == 0);
            } else if (segY != 0) {
                sameDir = (dy == Integer.signum(segY) && dx == 0);
            }
            
            //либо продолжает текущий сегмент либо создаёт новый
            if (sameDir) {
                segX += dx;
                segY += dy;
            } else {
                points.add(Coord.newBuilder().setX(segX).setY(segY).build());
                segX = dx;
                segY = dy;
            }
        }
        
        //добавляет последний сегмент
        points.add(Coord.newBuilder().setX(segX).setY(segY).build());
        
        return points;
    }
    
    //проверяет столкновения змей между собой и сами с собой
    private void checkCollisions() {
        //собирает позиции голов всех змей
        Map<String, List<Integer>> headPositions = new HashMap<>();
        Set<Integer> deadSnakes = new HashSet<>();
        
        for (Snake snake : field.getSnakes().values()) {
            if (snake.state != GameState.Snake.SnakeState.ALIVE && 
                snake.state != GameState.Snake.SnakeState.ZOMBIE) continue;
                
            List<Coord> cells = snake.getAllCells(field.getWidth(), field.getHeight());
            if (cells.isEmpty()) continue;
            
            Coord head = cells.get(0);
            String posKey = head.getX() + "," + head.getY();
            headPositions.computeIfAbsent(posKey, k -> new ArrayList<>()).add(snake.playerId);
        }
        
        //если несколько змей столкнулись головами все они погибают
        for (List<Integer> playerIds : headPositions.values()) {
            if (playerIds.size() > 1) {
                deadSnakes.addAll(playerIds);
            }
        }
        
        //проверяет столкновение каждой змеи с телами других змей
        for (Snake snake : field.getSnakes().values()) {
            if (snake.state != GameState.Snake.SnakeState.ALIVE && 
                snake.state != GameState.Snake.SnakeState.ZOMBIE) continue;
            
            if (deadSnakes.contains(snake.playerId)) continue;
                
            List<Coord> cells = snake.getAllCells(field.getWidth(), field.getHeight());
            if (cells.isEmpty()) continue;
            
            Coord head = cells.get(0);
            int headX = head.getX();
            int headY = head.getY();
            
            //проверяет столкновение со всеми змеями включая себя
            for (Snake other : field.getSnakes().values()) {
                if (other.state != GameState.Snake.SnakeState.ALIVE && 
                    other.state != GameState.Snake.SnakeState.ZOMBIE) continue;
                    
                List<Coord> otherCells = other.getAllCells(field.getWidth(), field.getHeight());
                
                //для своей змеи проверяет начиная со второго сегмента
                int startIdx = (other.playerId == snake.playerId) ? 1 : 0;
                
                for (int i = startIdx; i < otherCells.size(); i++) {
                    Coord cell = otherCells.get(i);
                    if (cell.getX() == headX && cell.getY() == headY) {
                        deadSnakes.add(snake.playerId);
                        
                        //начисляет очко игроку чьё тело столкнулось с головой
                        if (other.playerId != snake.playerId && other.state == GameState.Snake.SnakeState.ALIVE) {
                            PlayerInfo player = field.getPlayer(other.playerId);
                            if (player != null) {
                                player.score++;
                            }
                        }
                        break;
                    }
                }
                if (deadSnakes.contains(snake.playerId)) break;
            }
        }
        
        //превращает все мёртвые змеи в еду
        for (int playerId : deadSnakes) {
            Snake snake = field.getSnakes().get(playerId);
            if (snake != null) {
                removeSnakeToFood(snake);
            }
        }
    }
    
    //превращает мёртвую змею в еду с вероятностью 50% для каждой клетки
    private void removeSnakeToFood(Snake snake) {
        List<Coord> cells = snake.getAllCells(field.getWidth(), field.getHeight());
        
        //каждая клетка змеи может стать едой с вероятностью 50%
        for (Coord cell : cells) {
            if (random.nextDouble() < 0.5) {
                boolean exists = false;
                for (Coord f : field.getFoods()) {
                    if (f.getX() == cell.getX() && f.getY() == cell.getY()) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    field.addFood(cell);
                }
            }
        }
        
        //удаляет змею с поля
        field.removeSnake(snake.playerId);
        
        //переводит игрока в режим наблюдателя
        PlayerInfo player = field.getPlayer(snake.playerId);
        if (player != null) {
            player.role = NodeRole.VIEWER;
        }
    }
    
    private void removeDeadSnakes() {
    }
    
    //создаёт новую еду на поле в соответствии с правилами игры
    private void spawnFood() {
        //вычисляет количество живых змей
        int aliveSnakes = (int) field.getSnakes().values().stream()
                .filter(s -> s.state == GameState.Snake.SnakeState.ALIVE)
                .count();
        
        //требуемое количество еды = статичная еда + количество живых змей
        int requiredFood = config.getFoodStatic() + aliveSnakes;
        int currentFood = field.getFoods().size();
        int toSpawn = requiredFood - currentFood;
        
        //собирает все занятые клетки
        Set<String> occupiedCells = new HashSet<>();
        for (Snake snake : field.getSnakes().values()) {
            for (Coord cell : snake.getAllCells(field.getWidth(), field.getHeight())) {
                occupiedCells.add(cell.getX() + "," + cell.getY());
            }
        }
        for (Coord food : field.getFoods()) {
            occupiedCells.add(food.getX() + "," + food.getY());
        }
        
        int attempts = 0;
        int maxAttempts = field.getWidth() * field.getHeight();
        
        //пытается разместить еду на свободных клетках
        while (toSpawn > 0 && attempts < maxAttempts) {
            int x = random.nextInt(field.getWidth());
            int y = random.nextInt(field.getHeight());
            String key = x + "," + y;
            
            if (!occupiedCells.contains(key)) {
                Coord food = Coord.newBuilder().setX(x).setY(y).build();
                field.addFood(food);
                occupiedCells.add(key);
                toSpawn--;
            }
            attempts++;
        }
    }
    
    //размещает новую змею для игрока на случайной свободной позиции
    public boolean placeNewSnake(int playerId) {
        //делает до 100 попыток найти свободное место
        for (int attempt = 0; attempt < 100; attempt++) {
            int centerX = random.nextInt(field.getWidth());
            int centerY = random.nextInt(field.getHeight());
            
            //проверяет свободна ли область 5х5 клеток
            if (!field.canPlaceSnake(centerX, centerY)) {
                continue;
            }
            
            //выбирает случайное направление для хвоста
            Direction[] directions = {Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT};
            Direction tailDir = directions[random.nextInt(directions.length)];
            
            int tailX = centerX;
            int tailY = centerY;
            
            //вычисляет позицию хвоста в противоположном направлении
            switch (tailDir) {
                case UP: tailY++; break;
                case DOWN: tailY--; break;
                case LEFT: tailX++; break;
                case RIGHT: tailX--; break;
            }
            
            tailX = (tailX + field.getWidth()) % field.getWidth();
            tailY = (tailY + field.getHeight()) % field.getHeight();
            
            //проверяет что на змее нет еды
            boolean hasFoodOnSnake = false;
            for (Coord food : field.getFoods()) {
                if ((food.getX() == centerX && food.getY() == centerY) ||
                    (food.getX() == tailX && food.getY() == tailY)) {
                    hasFoodOnSnake = true;
                    break;
                }
            }
            if (hasFoodOnSnake) continue;
            
            //направление головы противоположно направлению хвоста
            Direction headDir = getOppositeDirection(tailDir);
            
            Coord head = Coord.newBuilder().setX(centerX).setY(centerY).build();
            
            //вычисляет смещение хвоста относительно головы
            int dx = tailX - centerX;
            int dy = tailY - centerY;
            if (Math.abs(dx) > 1) dx = dx > 0 ? dx - field.getWidth() : dx + field.getWidth();
            if (Math.abs(dy) > 1) dy = dy > 0 ? dy - field.getHeight() : dy + field.getHeight();
            
            Coord tail = Coord.newBuilder().setX(dx).setY(dy).build();
            
            //создаёт змею из двух клеток
            List<Coord> points = Arrays.asList(head, tail);
            Snake snake = new Snake(playerId, points, headDir);
            
            field.addSnake(playerId, snake);
            return true;
        }
        
        //не удалось найти свободное место
        return false;
    }
    
    //проверяет являются ли два направления противоположными
    private boolean isOppositeDirection(Direction d1, Direction d2) {
        return (d1 == Direction.UP && d2 == Direction.DOWN) ||
               (d1 == Direction.DOWN && d2 == Direction.UP) ||
               (d1 == Direction.LEFT && d2 == Direction.RIGHT) ||
               (d1 == Direction.RIGHT && d2 == Direction.LEFT);
    }
    
    //возвращает противоположное направление
    private Direction getOppositeDirection(Direction d) {
        switch (d) {
            case UP: return Direction.DOWN;
            case DOWN: return Direction.UP;
            case LEFT: return Direction.RIGHT;
            case RIGHT: return Direction.LEFT;
            default: return Direction.UP;
        }
    }
    
    //строит protobuf сообщение с текущим состоянием игры
    public GameState buildGameState() {
        GameState.Builder stateBuilder = GameState.newBuilder();
        stateBuilder.setStateOrder(field.getStateOrder());
        
        //добавляет всех змей
        for (Snake snake : field.getSnakes().values()) {
            GameState.Snake.Builder snakeBuilder = GameState.Snake.newBuilder();
            snakeBuilder.setPlayerId(snake.playerId);
            snakeBuilder.addAllPoints(snake.points);
            snakeBuilder.setState(snake.state);
            snakeBuilder.setHeadDirection(snake.headDirection);
            stateBuilder.addSnakes(snakeBuilder.build());
        }
        
        //добавляет всю еду
        stateBuilder.addAllFoods(field.getFoods());
        
        //добавляет информацию о всех игроках
        GamePlayers.Builder playersBuilder = GamePlayers.newBuilder();
        for (PlayerInfo player : field.getPlayers().values()) {
            GamePlayer.Builder playerBuilder = GamePlayer.newBuilder();
            playerBuilder.setName(player.name);
            playerBuilder.setId(player.id);
            playerBuilder.setRole(player.role);
            playerBuilder.setScore(player.score);
            
            if (player.ipAddress != null) {
                playerBuilder.setIpAddress(player.ipAddress);
            }
            if (player.port > 0) {
                playerBuilder.setPort(player.port);
            }
            
            playersBuilder.addPlayers(playerBuilder.build());
        }
        
        stateBuilder.setPlayers(playersBuilder.build());
        
        return stateBuilder.build();
    }
    
    //применяет полученное состояние игры к текущему полю
    public void applyGameState(GameState state) {
        //игнорирует старые состояния
        if (state.getStateOrder() <= field.getStateOrder()) {
            return;
        }
        
        //очищает и восстанавливает змей
        field.getSnakes().clear();
        for (GameState.Snake protoSnake : state.getSnakesList()) {
            Snake snake = new Snake(protoSnake.getPlayerId(), 
                                   protoSnake.getPointsList(), 
                                   protoSnake.getHeadDirection());
            snake.state = protoSnake.getState();
            field.addSnake(snake.playerId, snake);
        }
        
        //очищает и восстанавливает еду
        field.getFoods().clear();
        for (Coord food : state.getFoodsList()) {
            field.addFood(food);
        }
        
        //очищает и восстанавливает игроков
        field.getPlayers().clear();
        for (GamePlayer player : state.getPlayers().getPlayersList()) {
            PlayerInfo info = new PlayerInfo(player.getName(), player.getId(), player.getRole());
            info.score = player.getScore();
            if (player.hasIpAddress()) {
                info.ipAddress = player.getIpAddress();
            }
            if (player.hasPort()) {
                info.port = player.getPort();
            }
            field.addPlayer(info);
        }
        
        //синхронизирует счётчик порядка состояния
        while (field.getStateOrder() < state.getStateOrder()) {
            field.incrementStateOrder();
        }
    }
    
    //обновляет роль игрока в игре
    public void updatePlayerRole(int playerId, NodeRole role) {
        PlayerInfo player = field.getPlayer(playerId);
        if (player != null) {
            player.role = role;
        }
    }
}
