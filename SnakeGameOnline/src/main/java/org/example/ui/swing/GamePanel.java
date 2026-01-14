package org.example.ui.swing;

import me.ippolitov.fit.snakes.SnakesProto.*;
import me.ippolitov.fit.snakes.SnakesProto.GameState.Coord;
import org.example.controller.GameController;
import org.example.model.GameEngine;
import org.example.model.GameField;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

//панель игрового процесса с отрисовкой поля и управлением
public class GamePanel extends JPanel {
    private final Consumer<Direction> onSteer; //callback для отправки команды движения
    private final Runnable onQuit; //callback для выхода из игры
    private final Runnable onBecomeViewer; //callback для перехода в режим наблюдателя
    private final Runnable onRejoin; //callback для переподключения к игре
    private final Runnable onStartNewGame; //callback для создания новой игры
    private final Consumer<GameController.GameInfo> onJoinOtherGame; //callback для присоединения к другой игре
    private final GameController controller;
    
    //границы кнопок для обработки кликов мыши
    private Rectangle exitButtonBounds;
    private Rectangle rejoinButtonBounds;
    private Rectangle leaveButtonBounds;
    private Rectangle newGameButtonBounds;
    private java.util.List<GameClickArea> gameClickAreas = new ArrayList<>();
    
    //хранит область клика для игры в списке доступных
    private static class GameClickArea {
        Rectangle bounds;
        GameController.GameInfo gameInfo;
        
        GameClickArea(Rectangle bounds, GameController.GameInfo gameInfo) {
            this.bounds = bounds;
            this.gameInfo = gameInfo;
        }
    }
    
    //цвета для отрисовки разных змей
    private static final Color[] PLAYER_COLORS = {
        new Color(0, 200, 0),    //зелёный
        new Color(200, 0, 0),    //красный
        new Color(200, 200, 0),  //жёлтый
        new Color(0, 0, 200),    //синий
        new Color(200, 0, 200),  //пурпурный
        new Color(0, 200, 200)   //голубой
    };
    
    private static final int CELL_SIZE = 15; //размер одной клетки в пикселях
    private static final int RIGHT_PANEL_WIDTH = 320; //ширина правой информационной панели
    
    //создаёт панель игры с указанными callback функциями
    public GamePanel(Consumer<Direction> onSteer, Runnable onQuit,
                    Runnable onBecomeViewer, Runnable onRejoin,
                    Runnable onStartNewGame, Consumer<GameController.GameInfo> onJoinOtherGame,
                    GameController controller) {
        this.onSteer = onSteer;
        this.onQuit = onQuit;
        this.onBecomeViewer = onBecomeViewer;
        this.onRejoin = onRejoin;
        this.onStartNewGame = onStartNewGame;
        this.onJoinOtherGame = onJoinOtherGame;
        this.controller = controller;
        
        setFocusable(true);
        setBackground(Color.BLACK);
        
        //обработка нажатий клавиш для управления змеёй
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyPress(e.getKeyCode());
            }
        });
        
        //обработка кликов мыши по кнопкам
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleMouseClick(e.getX(), e.getY());
            }
        });
    }
    
    //обрабатывает клики мыши по интерактивным элементам
    private void handleMouseClick(int x, int y) {
        if (exitButtonBounds != null && exitButtonBounds.contains(x, y)) {
            if (onBecomeViewer != null) {
                onBecomeViewer.run();
            }
        } else if (rejoinButtonBounds != null && rejoinButtonBounds.contains(x, y)) {
            if (onRejoin != null) {
                onRejoin.run();
            }
        } else if (leaveButtonBounds != null && leaveButtonBounds.contains(x, y)) {
            if (onQuit != null) {
                onQuit.run();
            }
        } else if (newGameButtonBounds != null && newGameButtonBounds.contains(x, y)) {
            if (onStartNewGame != null) {
                onStartNewGame.run();
            }
        } else {
            //проверяет клик по играм в списке доступных
            for (GameClickArea area : gameClickAreas) {
                if (area.bounds.contains(x, y)) {
                    if (onJoinOtherGame != null) {
                        onJoinOtherGame.accept(area.gameInfo);
                    }
                    break;
                }
            }
        }
    }
    
    //обрабатывает нажатия клавиш для управления змеёй
    private void handleKeyPress(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_W:
            case KeyEvent.VK_UP:
                onSteer.accept(Direction.UP);
                break;
            case KeyEvent.VK_S:
            case KeyEvent.VK_DOWN:
                onSteer.accept(Direction.DOWN);
                break;
            case KeyEvent.VK_A:
            case KeyEvent.VK_LEFT:
                onSteer.accept(Direction.LEFT);
                break;
            case KeyEvent.VK_D:
            case KeyEvent.VK_RIGHT:
                onSteer.accept(Direction.RIGHT);
                break;
            case KeyEvent.VK_Q:
            case KeyEvent.VK_ESCAPE:
                onQuit.run();
                break;
            case KeyEvent.VK_V:
                if (onBecomeViewer != null) {
                    onBecomeViewer.run();
                }
                break;
            case KeyEvent.VK_R:
                if (onRejoin != null) {
                    onRejoin.run();
                }
                break;
            case KeyEvent.VK_N:
                if (onStartNewGame != null) {
                    onStartNewGame.run();
                }
                break;
            case KeyEvent.VK_L:
                onQuit.run();
                break;
        }
    }
    
    //возвращает предпочтительный размер панели на основе размера поля
    @Override
    public Dimension getPreferredSize() {
        GameEngine engine = controller.getGameEngine();
        if (engine != null) {
            GameField field = engine.getField();
            return new Dimension(field.getWidth() * CELL_SIZE + RIGHT_PANEL_WIDTH, 
                               field.getHeight() * CELL_SIZE + 40);
        }
        return new Dimension(800, 600);
    }
    
    //отрисовывает игровое поле и интерфейс
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        GameEngine engine = controller.getGameEngine();
        if (engine == null) return;
        
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        GameField field = engine.getField();
        int width = field.getWidth();
        
        drawField(g2d, field, engine);
        drawRightPanel(g2d, field, width * CELL_SIZE + 10, engine);
    }
    
    //отрисовывает игровое поле с едой и змеями
    private void drawField(Graphics2D g, GameField field, GameEngine engine) {
        int width = field.getWidth();
        int height = field.getHeight();
        
        //рисует фон поля
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, width * CELL_SIZE, height * CELL_SIZE);
        
        //рисует сетку
        g.setColor(new Color(50, 50, 50));
        for (int x = 0; x <= width; x++) {
            g.drawLine(x * CELL_SIZE, 0, x * CELL_SIZE, height * CELL_SIZE);
        }
        for (int y = 0; y <= height; y++) {
            g.drawLine(0, y * CELL_SIZE, width * CELL_SIZE, y * CELL_SIZE);
        }
        
        //рисует еду
        for (Coord food : field.getFoods()) {
            g.setColor(Color.RED);
            g.fillOval(food.getX() * CELL_SIZE + 2, food.getY() * CELL_SIZE + 2, 
                      CELL_SIZE - 4, CELL_SIZE - 4);
        }
        
        //рисует змей разными цветами
        int playerIndex = 0;
        for (GameField.Snake snake : field.getSnakes().values()) {
            Color color = PLAYER_COLORS[playerIndex % PLAYER_COLORS.length];
            List<Coord> cells = snake.getAllCells(width, height);
            
            for (int i = 0; i < cells.size(); i++) {
                Coord cell = cells.get(i);
                int x = cell.getX() * CELL_SIZE;
                int y = cell.getY() * CELL_SIZE;
                
                //голова змеи рисуется ярче
                if (i == 0) {
                    g.setColor(color.brighter());
                    g.fillRect(x + 1, y + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                    
                    //голова змеи игрока выделяется белой рамкой
                    if (snake.playerId == controller.getMyPlayerId()) {
                        g.setColor(Color.WHITE);
                        g.drawRect(x, y, CELL_SIZE - 1, CELL_SIZE - 1);
                    }
                } else {
                    //тело змеи
                    g.setColor(color);
                    g.fillRect(x + 2, y + 2, CELL_SIZE - 4, CELL_SIZE - 4);
                }
            }
            playerIndex++;
        }
    }
    
    //рисует правую информационную панель
    private void drawRightPanel(Graphics2D g, GameField field, int panelX, GameEngine engine) {
        int x = panelX + 5;
        int y = 5;
        int panelWidth = RIGHT_PANEL_WIDTH - 15;
        
        //блок информации о текущей игре
        int blockHeight = 110;
        drawBlock(g, panelX, y, panelWidth, blockHeight, "ТЕКУЩАЯ ИГРА");
        
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 12));
        
        String ownerName = controller.getOriginalMasterName();
        if (ownerName == null) ownerName = "Unknown";
        g.drawString("Владелец: " + ownerName, x, y + 35);
        
        GameConfig config = engine.getConfig();
        g.drawString("Поле: " + config.getWidth() + "x" + config.getHeight(), x, y + 50);
        g.drawString("Скорость: " + config.getStateDelayMs() + "мс", x, y + 65);
        g.drawString("Еда: " + config.getFoodStatic() + "+1x", x, y + 80);
        
        //отображает роль игрока с цветовым кодированием
        NodeRole myRole = controller.getMyRole();
        g.setColor(myRole == NodeRole.MASTER ? Color.YELLOW : 
                   myRole == NodeRole.DEPUTY ? Color.ORANGE : 
                   myRole == NodeRole.VIEWER ? Color.GRAY : Color.WHITE);
        g.drawString("Моя роль: " + getRoleString(myRole), x, y + 95);
        
        y += blockHeight + 5;
        
        //блок рейтинга игроков
        List<GameField.PlayerInfo> players = new ArrayList<>();
        for (GameField.PlayerInfo p : field.getPlayers().values()) {
            if (p.role != NodeRole.VIEWER) {
                players.add(p);
            }
        }
        players.sort((p1, p2) -> Integer.compare(p2.score, p1.score));
        
        int ratingBlockHeight = 25 + Math.min(players.size(), 6) * 18;
        drawBlock(g, panelX, y, panelWidth, ratingBlockHeight, "РЕЙТИНГ");
        
        g.setFont(new Font("Arial", Font.PLAIN, 11));
        int rank = 1;
        int currentPlayerId = controller.getMyPlayerId();
        int ratingY = y + 30;
        for (GameField.PlayerInfo player : players) {
            //рисует квадрат цвета змеи игрока
            Color color = PLAYER_COLORS[getPlayerColorIndex(field, player.id)];
            g.setColor(color);
            g.fillRect(x, ratingY, 10, 10);
            
            g.setColor(Color.WHITE);
            String text = String.format("%d. %s: %d", rank++, truncate(player.name, 12), player.score);
            
            //текущий игрок выделяется жирным шрифтом и стрелкой
            if (player.id == currentPlayerId) {
                g.setFont(new Font("Arial", Font.BOLD, 11));
                g.drawString("► " + text, x + 14, ratingY + 10);
                g.setFont(new Font("Arial", Font.PLAIN, 11));
            } else {
                g.drawString(text, x + 14, ratingY + 10);
            }
            ratingY += 18;
            if (rank > 6) break;
        }
        
        y += ratingBlockHeight + 5;
        
        //блок с кнопками действий
        int actionsBlockHeight = 120;
        drawBlock(g, panelX, y, panelWidth, actionsBlockHeight, "ДЕЙСТВИЯ");
        
        exitButtonBounds = null;
        rejoinButtonBounds = null;
        leaveButtonBounds = null;
        newGameButtonBounds = null;
        
        boolean isViewer = (controller.getCurrentState() == GameController.GameState.VIEWING);
        boolean isPlaying = (controller.getCurrentState() == GameController.GameState.IN_GAME);
        
        int btnY = y + 25;
        int btnWidth = panelWidth - 20;
        
        //кнопка выхода для активных игроков
        if (isPlaying && myRole != NodeRole.VIEWER) {
            exitButtonBounds = new Rectangle(x, btnY, btnWidth, 22);
            drawButton(g, x, btnY, btnWidth, 22, "Выход (V)", Color.ORANGE);
            btnY += 27;
        }
        
        //кнопки для наблюдателей
        if (isViewer || myRole == NodeRole.VIEWER) {
            rejoinButtonBounds = new Rectangle(x, btnY, btnWidth, 22);
            drawButton(g, x, btnY, btnWidth, 22, "Вернуться (R)", Color.GREEN);
            btnY += 27;
            
            leaveButtonBounds = new Rectangle(x, btnY, btnWidth, 22);
            drawButton(g, x, btnY, btnWidth, 22, "Покинуть (L)", Color.RED);
            btnY += 27;
        }
        
        newGameButtonBounds = new Rectangle(x, btnY, btnWidth, 22);
        drawButton(g, x, btnY, btnWidth, 22, "Новая игра (N)", Color.BLUE);
        
        y += actionsBlockHeight + 5;
        
        //блок списка доступных игр
        Map<String, GameController.GameInfo> availableGames = controller.getAvailableGames();
        
        String currentGame = controller.getCurrentGameName();
        java.util.List<GameController.GameInfo> filteredGames = new ArrayList<>();
        for (GameController.GameInfo info : availableGames.values()) {
            if (currentGame == null || !currentGame.equals(info.gameName)) {
                filteredGames.add(info);
            }
        }
        
        int gamesBlockHeight = 30 + Math.max(1, Math.min(filteredGames.size(), 4)) * 40;
        drawBlock(g, panelX, y, panelWidth, gamesBlockHeight, "ДОСТУПНЫЕ ИГРЫ");
        
        gameClickAreas.clear();
        
        int gamesY = y + 30;
        if (filteredGames.isEmpty()) {
            g.setColor(Color.GRAY);
            g.setFont(new Font("Arial", Font.ITALIC, 11));
            g.drawString("Нет доступных игр", x, gamesY);
        } else {
            g.setFont(new Font("Arial", Font.PLAIN, 10));
            int gameNum = 1;
            for (GameController.GameInfo info : filteredGames) {
                //находит имя мастера игры
                String masterName = "Unknown";
                for (GamePlayer p : info.players) {
                    if (p.getRole() == NodeRole.MASTER) {
                        masterName = p.getName();
                        break;
                    }
                }
                
                String ipAddr = info.masterAddress != null ?
                    info.masterAddress.getHostAddress() : "?";
                if (ipAddr.length() > 12) {
                    ipAddr = ipAddr.substring(0, 12) + "..";
                }
                
                g.setColor(Color.WHITE);
                g.drawString(String.format("#%d %s", gameNum, truncate(masterName, 10)), x, gamesY);
                
                g.setColor(Color.GRAY);
                g.drawString(String.format("%s | %dx%d", ipAddr,
                    info.config.getWidth(), info.config.getHeight()), x, gamesY + 12);
                
                Rectangle joinBounds = new Rectangle(x + panelWidth - 40, gamesY - 10, 35, 30);
                gameClickAreas.add(new GameClickArea(joinBounds, info));
                
                //кнопка присоединения к игре
                g.setColor(new Color(0, 150, 0));
                g.fillRoundRect(joinBounds.x, joinBounds.y, joinBounds.width, joinBounds.height, 5, 5);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Arial", Font.BOLD, 14));
                g.drawString("→", joinBounds.x + 12, gamesY + 8);
                g.setFont(new Font("Arial", Font.PLAIN, 10));
                
                gamesY += 40;
                gameNum++;
                if (gameNum > 4) break;
            }
        }
        
        y += gamesBlockHeight + 5;
        
        //блок подсказок по управлению
        int helpBlockHeight = 85;
        drawBlock(g, panelX, y, panelWidth, helpBlockHeight, "УПРАВЛЕНИЕ");
        
        g.setColor(Color.GRAY);
        g.setFont(new Font("Arial", Font.PLAIN, 10));
        g.drawString("WASD/Стрелки - движение", x, y + 30);
        g.drawString("V - стать вьювером", x, y + 42);
        g.drawString("R - вернуться в игру", x, y + 54);
        g.drawString("N - новая игра  |  L - покинуть", x, y + 66);
    }
    
    //рисует блок с заголовком
    private void drawBlock(Graphics2D g, int x, int y, int width, int height, String title) {
        g.setColor(new Color(40, 40, 50));
        g.fillRoundRect(x, y, width, height, 8, 8);
        
        g.setColor(new Color(70, 70, 90));
        g.drawRoundRect(x, y, width, height, 8, 8);
        
        g.setColor(Color.CYAN);
        g.setFont(new Font("Arial", Font.BOLD, 12));
        g.drawString(title, x + 8, y + 16);
    }
    
    //рисует кликабельную кнопку
    private void drawButton(Graphics2D g, int x, int y, int width, int height, String text, Color color) {
        g.setColor(color.darker());
        g.fillRoundRect(x, y, width, height, 5, 5);
        g.setColor(color);
        g.drawRoundRect(x, y, width, height, 5, 5);
        
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int textX = x + (width - fm.stringWidth(text)) / 2;
        int textY = y + (height + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, textX, textY);
    }
    
    //определяет индекс цвета для игрока
    private int getPlayerColorIndex(GameField field, int playerId) {
        List<Integer> playerIds = new ArrayList<>(field.getSnakes().keySet());
        Collections.sort(playerIds);
        int index = playerIds.indexOf(playerId);
        
        if (index == -1) {
            return playerId % PLAYER_COLORS.length;
        }
        
        return index % PLAYER_COLORS.length;
    }
    
    //возвращает текстовое название роли на русском
    private String getRoleString(NodeRole role) {
        switch (role) {
            case MASTER: return "Мастер";
            case DEPUTY: return "Игрок";
            case VIEWER: return "Вьювер";
            default: return "Игрок";
        }
    }
    
    //возвращает короткое обозначение роли
    private String getRoleShortString(NodeRole role) {
        switch (role) {
            case MASTER: return "M";
            case DEPUTY: return "D";
            case VIEWER: return "V";
            default: return "N";
        }
    }
    
    //обрезает строку до заданной длины добавляя многоточие
    private String truncate(String str, int maxLen) {
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 2) + "..";
    }
}
