package org.example.controller;

import me.ippolitov.fit.snakes.SnakesProto.*;
import org.example.model.GameEngine;
import org.example.model.GameField.PlayerInfo;
import org.example.network.NetworkManager;
import org.example.ui.IGameUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class GameController {
    private static final Logger logger = LoggerFactory.getLogger(GameController.class);
    
    private IGameUI ui;
    private NetworkManager networkManager;
    private GameEngine gameEngine;
    
    private final Map<String, GameInfo> availableGames = new ConcurrentHashMap<>(); //доступные игры в сети
    private final AtomicInteger playerIdCounter = new AtomicInteger(0); //счётчик id игроков
    
    private volatile GameState currentState = GameState.IDLE; //текущее состояние игры
    private volatile NodeRole myRole = NodeRole.NORMAL; //роль этого узла в сети
    private volatile int myPlayerId = -1; //id этого игрока
    private volatile String myPlayerName = ""; //имя этого игрока
    
    private InetAddress masterAddress;
    private int masterPort;
    private InetAddress deputyAddress;
    private int deputyPort;
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private ScheduledFuture<?> gameLoopTask; //игровой цикл
    private ScheduledFuture<?> announcementTask; //отправка обьявлений
    private ScheduledFuture<?> networkTask; //обработка сети
    
    private MessageHandler messageHandler; //обработчик входящих сообщений
    private int deputyPlayerId = -1; //id заместителя
    
    private String currentGameName = null; //название текущей игры
    private String gameMasterName = null; //имя мастера игры
    
    //возможные состояния игры для узла
    public enum GameState {
        IDLE, //не в игре
        IN_GAME, //активно играет
        VIEWING //наблюдает за игрой
    }
    
    //информация о доступной игре в сети
    public static class GameInfo {
        public String gameName; //название игры
        public GameConfig config; //конфигурация игры
        public List<GamePlayer> players; //список игроков
        public boolean canJoin; //можно ли присоединиться
        public InetAddress masterAddress; //адрес мастера игры
        public int masterPort; //порт мастера
        public long lastSeen; //время последнего обновления
        
        //создаёт информацию об игре
        public GameInfo(String gameName, GameConfig config, List<GamePlayer> players, 
                       boolean canJoin, InetAddress address, int port) {
            this.gameName = gameName;
            this.config = config;
            this.players = new ArrayList<>(players);
            this.canJoin = canJoin;
            this.masterAddress = address;
            this.masterPort = port;
            this.lastSeen = System.currentTimeMillis();
        }
        
        //обновляет информацию об игре
        public void update(List<GamePlayer> players, boolean canJoin) {
            this.players = new ArrayList<>(players);
            this.canJoin = canJoin;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    //создаёт контроллер и инициализирует сетевой менеджер
    public GameController() {
        try {
            networkManager = new NetworkManager();
            messageHandler = new MessageHandler(this);
        } catch (IOException e) {
            logger.error("Failed to initialize network manager", e);
        }
    }
    
    //устанавливает пользовательский интерфейс и запускает сетевые задачи
    public void setUI(IGameUI ui) {
        this.ui = ui;
        startNetworkTasks();
    }
    
    //запускает фоновые задачи для обработки сети и проверки таймаутов
    private void startNetworkTasks() {
        //задача обработки входящих сообщений каждые 10 мс
        networkTask = scheduler.scheduleAtFixedRate(
                this::processNetworkMessages,
                0,
                10,
                TimeUnit.MILLISECONDS
        );
        
        //задача проверки таймаутов каждые 100 мс
        scheduler.scheduleAtFixedRate(
                this::checkTimeouts,
                0,
                100,
                TimeUnit.MILLISECONDS
        );
        
        //задача очистки старых игр каждые 2 секунды
        scheduler.scheduleAtFixedRate(
                this::cleanupOldGames,
                0,
                2000,
                TimeUnit.MILLISECONDS
        );
    }
    
    //обрабатывает входящие unicast и multicast сообщения
    private void processNetworkMessages() {
        try {
            NetworkManager.ReceivedMessage unicastMsg = networkManager.receiveUnicast(5);
            if (unicastMsg != null) {
                logger.info("Received unicast: {} from {}:{}", 
                    getMessageType(unicastMsg.message),
                    unicastMsg.address.getHostAddress(), unicastMsg.port);
                messageHandler.handleMessage(unicastMsg);
            }
            
            NetworkManager.ReceivedMessage multicastMsg = networkManager.receiveMulticast(5);
            if (multicastMsg != null) {
                logger.info("Received multicast: {} from {}:{}", 
                    getMessageType(multicastMsg.message),
                    multicastMsg.address.getHostAddress(), multicastMsg.port);
                messageHandler.handleMessage(multicastMsg);
            }
        } catch (IOException e) {
            logger.error("Error processing network messages", e);
        }
    }
    
    //определяет тип сообщения для логирования
    private String getMessageType(GameMessage msg) {
        if (msg.hasAnnouncement()) return "Announcement";
        if (msg.hasJoin()) return "Join";
        if (msg.hasState()) return "State";
        if (msg.hasSteer()) return "Steer";
        if (msg.hasAck()) return "Ack";
        if (msg.hasPing()) return "Ping";
        if (msg.hasError()) return "Error";
        if (msg.hasRoleChange()) return "RoleChange";
        if (msg.hasDiscover()) return "Discover";
        return "Unknown";
    }
    
    //проверяет таймауты соединений и отправляет ping сообщения
    private void checkTimeouts() {
        if (gameEngine == null || currentState == GameState.IDLE) {
            return;
        }
        
        //вычисляет таймаут как 80% от задержки обновления состояния
        int stateDelayMs = gameEngine.getConfig().getStateDelayMs();
        long timeoutMs = (long) (stateDelayMs * 0.8);
        
        timeoutMs = Math.max(timeoutMs, 3000);
        
        //повторно отправляет неподтверждённые сообщения
        try {
            networkManager.retransmitPending(stateDelayMs / 10);
        } catch (IOException e) {
            logger.error("Error retransmitting messages", e);
        }
        
        //проверяет таймаут мастера для обычных узлов и заместителей
        if ((myRole == NodeRole.NORMAL || myRole == NodeRole.DEPUTY) && myPlayerId >= 0) {
            if (masterAddress == null) return;
            
            long timeSinceMaster = networkManager.getTimeSinceLastMessage(
                masterAddress.getHostAddress(), masterPort);
            
            if (timeSinceMaster > timeoutMs) {
                logger.warn("MASTER timeout detected ({}ms > {}ms)", timeSinceMaster, timeoutMs);
                handleMasterTimeout();
            }
        }
        
        //проверяет таймауты всех игроков если этот узел мастер
        if (myRole == NodeRole.MASTER) {
            for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                if (player.id != myPlayerId && player.ipAddress != null) {
                    long timeSince = networkManager.getTimeSinceLastMessage(
                        player.ipAddress, player.port);
                    
                    if (timeSince > timeoutMs) {
                        logger.warn("Player {} timeout", player.id);
                        handlePlayerLeft(player.id);
                        networkManager.removeNodeTracking(player.ipAddress, player.port);
                    } else if (timeSince > stateDelayMs / 10) {
                        sendPing(player);
                    }
                }
            }
        }
    }
    
    //обрабатывает таймаут мастера выполняя переход власти или завершение игры
    private void handleMasterTimeout() {
        if (myRole == NodeRole.DEPUTY) {
            logger.info("MASTER timeout - promoting DEPUTY to MASTER");
            promoteToMaster();
        } else if (myRole == NodeRole.NORMAL) {
            PlayerInfo deputyPlayer = findDeputyPlayer();
            if (deputyPlayer != null && deputyPlayer.ipAddress != null) {
                logger.info("MASTER timeout - switching to DEPUTY at {}:{}", 
                    deputyPlayer.ipAddress, deputyPlayer.port);
                try {
                    InetAddress deputyAddr = InetAddress.getByName(deputyPlayer.ipAddress);
                    handleMasterChange(deputyAddr, deputyPlayer.port);
                } catch (Exception e) {
                    logger.error("Failed to switch to deputy", e);
                    endGameNobodyLeft();
                }
            } else {
                logger.error("MASTER timeout and no DEPUTY available - game over");
                endGameNobodyLeft();
            }
        }
    }
    
    //находит игрока с ролью заместителя
    private PlayerInfo findDeputyPlayer() {
        if (gameEngine == null) return null;
        for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
            if (player.role == NodeRole.DEPUTY) {
                return player;
            }
        }
        return null;
    }
    
    //завершает игру когда не осталось активных игроков
    private void endGameNobodyLeft() {
        if (currentState == GameState.IDLE) {
            return;
        }
        logger.warn("Game ending: no players left");
        stopGame();
    }
    
    //отправляет ping сообщение игроку для проверки соединения
    private void sendPing(PlayerInfo player) {
        try {
            InetAddress addr = InetAddress.getByName(player.ipAddress);
            GameMessage pingMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setSenderId(myPlayerId)
                    .setPing(GameMessage.PingMsg.newBuilder().build())
                    .build();
            
            networkManager.sendUnicast(pingMsg, addr, player.port);
        } catch (Exception e) {
            logger.error("Failed to send ping to player {}", player.id, e);
        }
    }
    
    //удаляет устаревшие игры из списка доступных
    private void cleanupOldGames() {
        long now = System.currentTimeMillis();
        availableGames.entrySet().removeIf(entry -> {
            boolean isOld = (now - entry.getValue().lastSeen) > 5000;
            if (isOld) {
                logger.debug("Removed stale game: {}", entry.getKey());
            }
            return isOld;
        });
    }
    
    public GameState getCurrentState() {
        return currentState;
    }
    
    public NodeRole getMyRole() {
        return myRole;
    }
    
    public int getMyPlayerId() {
        return myPlayerId;
    }
    
    public Map<String, GameInfo> getAvailableGames() {
        return new HashMap<>(availableGames);
    }
    
    public GameEngine getGameEngine() {
        return gameEngine;
    }
    
    public NetworkManager getNetworkManager() {
        return networkManager;
    }
    
    public IGameUI getUI() {
        return ui;
    }
    
    public int getDeputyPlayerId() {
        return deputyPlayerId;
    }
    
    public String getOriginalMasterName() {
        return gameMasterName;
    }
    
    public String getCurrentGameName() {
        return currentGameName;
    }
    
    public String getMyPlayerName() {
        return myPlayerName;
    }
    
    public InetAddress getMasterAddress() {
        return masterAddress;
    }
    
    public int getMasterPort() {
        return masterPort;
    }
    
    //добавляет информацию об игре в список доступных
    public void addAvailableGame(String gameName, GameInfo gameInfo) {
        availableGames.put(gameName, gameInfo);
    }
    
    //генерирует следующий уникальный id игрока
    public int getNextPlayerId() {
        return playerIdCounter.incrementAndGet();
    }
    
    //добавляет нового игрока в игру
    public void addPlayer(int playerId, String playerName, NodeRole role, String ipAddress, int port) {
        if (gameEngine == null) return;
        
        PlayerInfo player = new PlayerInfo(playerName, playerId, role);
        player.ipAddress = ipAddress;
        player.port = port;
        gameEngine.getField().addPlayer(player);
    }
    
    //назначает игрока заместителем и отправляет ему уведомление
    public void setDeputy(int playerId, InetAddress address, int port) {
        this.deputyPlayerId = playerId;
        this.deputyAddress = address;
        this.deputyPort = port;
        
        if (gameEngine != null) {
            PlayerInfo player = gameEngine.getField().getPlayer(playerId);
            if (player != null) {
                player.role = NodeRole.DEPUTY;
            }
        }
        
        try {
            GameMessage roleChangeMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setSenderId(myPlayerId)
                    .setReceiverId(playerId)
                    .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                            .setReceiverRole(NodeRole.DEPUTY)
                            .build())
                    .build();
            
            networkManager.sendUnicast(roleChangeMsg, address, port);
        } catch (IOException e) {
            logger.error("Failed to send DEPUTY role change", e);
        }
    }
    
    //отправляет сообщение об ошибке указанному адресату
    public void sendError(String errorMessage, InetAddress address, int port) {
        try {
            GameMessage errorMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setError(GameMessage.ErrorMsg.newBuilder()
                            .setErrorMessage(errorMessage)
                            .build())
                    .build();
            
            networkManager.sendUnicast(errorMsg, address, port);
        } catch (IOException e) {
            logger.error("Failed to send error message", e);
        }
    }
    
    //отправляет подтверждение получения сообщения
    public void sendAck(long msgSeq, int receiverId, InetAddress address, int port) {
        try {
            GameMessage.Builder ackBuilder = GameMessage.newBuilder()
                    .setMsgSeq(msgSeq)
                    .setAck(GameMessage.AckMsg.newBuilder().build());
            
            if (myPlayerId >= 0) {
                ackBuilder.setSenderId(myPlayerId);
            }
            if (receiverId >= 0) {
                ackBuilder.setReceiverId(receiverId);
            }
            
            networkManager.sendUnicast(ackBuilder.build(), address, port);
        } catch (IOException e) {
            logger.error("Failed to send ACK", e);
        }
    }
    
    //обрабатывает отключение игрока делая его зомби или наблюдателем
    public void handlePlayerLeft(int playerId) {
        if (gameEngine == null) return;
        
        var snake = gameEngine.getField().getSnakes().get(playerId);
        if (snake != null) {
            snake.state = me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE;
        }
        
        PlayerInfo player = gameEngine.getField().getPlayer(playerId);
        if (player != null) {
            player.role = NodeRole.VIEWER;
        }
        
        //если отключился заместитель выбирает нового
        if (playerId == deputyPlayerId && myRole == NodeRole.MASTER) {
            selectNewDeputy();
        }
    }
    
    //обновляет адрес мастера в контроллере и сетевом менеджере
    public void handleMasterChange(InetAddress newMasterAddress, int newMasterPort) {
        this.masterAddress = newMasterAddress;
        this.masterPort = newMasterPort;
        networkManager.updateMasterAddress(newMasterAddress, newMasterPort);
    }
    
    //устанавливает роль заместителя для этого узла
    public void setMyRoleDeputy(InetAddress masterAddr, int masterPrt) {
        this.myRole = NodeRole.DEPUTY;
        this.masterAddress = masterAddr;
        this.masterPort = masterPrt;
        //перенаправляет unicast сообщения новому мастеру
        if (networkManager != null && masterAddr != null && masterPrt > 0) {
            networkManager.updateMasterAddress(masterAddr, masterPrt);
        }
        
        if (gameEngine != null) {
            PlayerInfo player = gameEngine.getField().getPlayer(myPlayerId);
            if (player != null) {
                player.role = NodeRole.DEPUTY;
            }
        }
    }
    
    //повышает этот узел до роли мастера
    public void promoteToMaster() {
        logger.info("Promoting to MASTER from {}, myPlayerId={}", this.myRole, this.myPlayerId);
        
        int savedPlayerId = this.myPlayerId;
        
        this.myRole = NodeRole.MASTER;
        
        if (gameEngine != null) {
            PlayerInfo player = gameEngine.getField().getPlayer(savedPlayerId);
            if (player != null) {
                player.role = NodeRole.MASTER;
                logger.info("Updated player {} role to MASTER", savedPlayerId);
            }
            
            //проверяет жива ли змея нового мастера
            var mySnake = gameEngine.getField().getSnakes().get(savedPlayerId);
            boolean isDead = (mySnake == null || 
                            mySnake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE);
            
            if (isDead) {
                logger.info("Promoted to MASTER but snake is dead - setting as VIEWER");
                currentState = GameState.VIEWING;
                if (player != null) {
                    player.role = NodeRole.VIEWER;
                }
                
                checkForPlayingMaster();
            }
        }
        
        this.masterAddress = null;
        this.masterPort = 0;
        
        //запускает задачи мастера
        if (gameEngine != null) {
            startMasterTasks(gameEngine.getConfig().getStateDelayMs());
        }
        
        selectNewDeputy();
        
        notifyAllPlayers();
        
        logger.info("Promotion complete. myPlayerId={}, myRole={}", this.myPlayerId, this.myRole);
    }
    
    //проверяет остались ли живые игроки после повышения мастера
    private void checkForPlayingMaster() {
        if (gameEngine == null) return;
        
        List<PlayerInfo> alivePlayers = new ArrayList<>();
        for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
            if (player.id != myPlayerId) {
                var snake = gameEngine.getField().getSnakes().get(player.id);
                boolean isAlive = (snake != null && 
                    snake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ALIVE);
                if (isAlive) {
                    alivePlayers.add(player);
                }
            }
        }
        
        if (alivePlayers.isEmpty()) {
            logger.warn("No alive players left - game should end");
            endGameNobodyLeft();
        }
    }
    
    //понижает узел до роли наблюдателя
    public void demoteToViewer() {
        NodeRole oldRole = this.myRole;
        this.myRole = NodeRole.VIEWER;
        this.currentState = GameState.VIEWING;
        
        if (gameEngine != null) {
            var snake = gameEngine.getField().getSnakes().get(myPlayerId);
            if (snake != null) {
                snake.state = me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE;
            }
            
            PlayerInfo player = gameEngine.getField().getPlayer(myPlayerId);
            if (player != null) {
                player.role = NodeRole.VIEWER;
            }
        }
        
        //уведомляет мастера о смене роли
        if (oldRole == NodeRole.MASTER && masterAddress != null) {
            try {
                GameMessage roleChangeMsg = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setSenderId(myPlayerId)
                        .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(NodeRole.VIEWER)
                                .build())
                        .build();
                
                networkManager.sendUnicast(roleChangeMsg, masterAddress, masterPort);
            } catch (IOException e) {
                logger.error("Failed to send viewer role change", e);
            }
        }
    }
    
    //переводит узел в режим наблюдения передавая мастерство заместителю
    public void becomeViewer() {
        if (currentState == GameState.IDLE) {
            return;
        }
        
        logger.info("Becoming viewer, myRole={}, myPlayerId={}", myRole, myPlayerId);
        
        //если мастер передаёт власть заместителю
        if (myRole == NodeRole.MASTER) {
            if (deputyPlayerId >= 0 && deputyAddress != null) {
                try {
                    GameMessage roleChangeMsg = GameMessage.newBuilder()
                            .setMsgSeq(networkManager.getNextMsgSeq())
                            .setSenderId(myPlayerId)
                            .setReceiverId(deputyPlayerId)
                            .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                    .setReceiverRole(NodeRole.MASTER)
                                    .build())
                            .build();
                    
                    networkManager.sendUnicast(roleChangeMsg, deputyAddress, deputyPort);
                    
                    this.masterAddress = deputyAddress;
                    this.masterPort = deputyPort;
                    
                    //останавливает задачи мастера
                    if (gameLoopTask != null) {
                        gameLoopTask.cancel(false);
                        gameLoopTask = null;
                    }
                    if (announcementTask != null) {
                        announcementTask.cancel(false);
                        announcementTask = null;
                    }
                    
                    logger.info("Transferred MASTER role to deputy {}", deputyPlayerId);
                } catch (IOException e) {
                    logger.error("Failed to transfer master role", e);
                }
            } else {
                logger.warn("No deputy to transfer MASTER role to");
            }
        }
        
        //уведомляет мастера о переходе в режим наблюдателя
        if (masterAddress != null && myPlayerId >= 0) {
            try {
                GameMessage roleChangeMsg = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setSenderId(myPlayerId)
                        .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(NodeRole.VIEWER)
                                .build())
                        .build();
                
                networkManager.sendUnicast(roleChangeMsg, masterAddress, masterPort);
            } catch (IOException e) {
                logger.error("Failed to send viewer role change", e);
            }
        }
        
        this.myRole = NodeRole.VIEWER;
        this.currentState = GameState.VIEWING;
        
        if (gameEngine != null) {
            var snake = gameEngine.getField().getSnakes().get(myPlayerId);
            if (snake != null) {
                snake.state = me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE;
            }
            
            PlayerInfo player = gameEngine.getField().getPlayer(myPlayerId);
            if (player != null) {
                player.role = NodeRole.VIEWER;
            }
        }
        
        if (ui != null) {
            ui.onGameStateUpdated();
        }
    }
    
    //позволяет наблюдателю или мёртвому мастеру снова присоединиться к игре
    public void rejoinGame() {
        logger.info("rejoinGame called: currentState={}, myRole={}, myPlayerId={}", 
            currentState, myRole, myPlayerId);
        
        boolean isViewer = (currentState == GameState.VIEWING || myRole == NodeRole.VIEWER);
        boolean isDeadMaster = (myRole == NodeRole.MASTER && isMySnakeDead());
        
        logger.info("isViewer={}, isDeadMaster={}, isMySnakeDead={}", 
            isViewer, isDeadMaster, isMySnakeDead());
        
        if (!isViewer && !isDeadMaster) {
            logger.warn("Cannot rejoin: not a viewer or dead master, state={}, role={}", currentState, myRole);
            return;
        }
        
        //мёртвый мастер может сразу создать новую змею
        if (isDeadMaster) {
            if (gameEngine != null) {
                logger.info("Dead master trying to place new snake, playerId={}", myPlayerId);
                boolean placed = gameEngine.placeNewSnake(myPlayerId);
                if (placed) {
                    currentState = GameState.IN_GAME;
                    PlayerInfo player = gameEngine.getField().getPlayer(myPlayerId);
                    if (player != null) {
                        player.role = NodeRole.MASTER;
                    }
                    logger.info("Master rejoined with new snake");
                    if (ui != null) {
                        ui.onGameStateUpdated();
                    }
                    return;
                } else {
                    logger.warn("Cannot rejoin: no space for new snake");
                    if (ui != null) {
                        ui.onError("Нет места для новой змейки");
                    }
                    return;
                }
            } else {
                logger.warn("Cannot rejoin: gameEngine is null");
                return;
            }
        }
        
        //наблюдатель отправляет запрос мастеру на присоединение
        if (masterAddress == null) {
            logger.warn("Cannot rejoin: no master address");
            if (ui != null) {
                ui.onError("Нет адреса мастера для подключения");
            }
            return;
        }
        
        logger.info("Rejoining game as player, sending JOIN to {}:{}", 
            masterAddress.getHostAddress(), masterPort);
        
        try {
            GameMessage joinMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setJoin(GameMessage.JoinMsg.newBuilder()
                            .setPlayerName(myPlayerName)
                            .setGameName(currentGameName != null ? currentGameName : "game")
                            .setRequestedRole(NodeRole.NORMAL)
                            .build())
                    .build();
            
            networkManager.sendUnicast(joinMsg, masterAddress, masterPort);
            
            this.myPlayerId = -1;
            this.myRole = NodeRole.NORMAL;
            this.currentState = GameState.IN_GAME;
            
            logger.info("Sent rejoin request to master at {}:{}", 
                masterAddress.getHostAddress(), masterPort);
        } catch (IOException e) {
            logger.error("Failed to send rejoin request", e);
            if (ui != null) {
                ui.onError("Failed to rejoin game");
            }
        }
    }
    
    //проверяет мертва ли змея этого игрока
    private boolean isMySnakeDead() {
        if (gameEngine == null || myPlayerId < 0) return true;
        var snake = gameEngine.getField().getSnakes().get(myPlayerId);
        return snake == null || snake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE;
    }
    
    //выбирает нового заместителя из живых игроков
    private void selectNewDeputy() {
        if (gameEngine == null) return;
        
        //собирает кандидатов среди живых обычных игроков
        List<PlayerInfo> candidates = new ArrayList<>();
        for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
            if (player.id != myPlayerId && 
                player.role == NodeRole.NORMAL && 
                player.ipAddress != null) {
                
                var snake = gameEngine.getField().getSnakes().get(player.id);
                boolean isAlive = (snake != null && 
                    snake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ALIVE);
                
                if (isAlive) {
                    candidates.add(player);
                }
            }
        }
        
        if (candidates.isEmpty()) {
            deputyPlayerId = -1;
            deputyAddress = null;
            deputyPort = 0;
            logger.info("No candidates for DEPUTY");
            return;
        }
        
        //выбирает игрока с наименьшим id
        candidates.sort(Comparator.comparingInt(p -> p.id));
        PlayerInfo newDeputy = candidates.get(0);
        
        try {
            InetAddress addr = InetAddress.getByName(newDeputy.ipAddress);
            setDeputy(newDeputy.id, addr, newDeputy.port);
            logger.info("Selected new DEPUTY: player {}", newDeputy.id);
        } catch (Exception e) {
            logger.error("Failed to set new deputy", e);
            deputyPlayerId = -1;
            deputyAddress = null;
            deputyPort = 0;
        }
    }
    
    //уведомляет всех игроков о смене роли мастера
    private void notifyAllPlayers() {
        if (gameEngine == null) return;
        
        try {
            GameMessage roleChangeMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setSenderId(myPlayerId)
                    .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                            .setSenderRole(NodeRole.MASTER)
                            .build())
                    .build();
            
            for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                if (player.id != myPlayerId && player.ipAddress != null) {
                    try {
                        InetAddress addr = InetAddress.getByName(player.ipAddress);
                        networkManager.sendUnicast(roleChangeMsg, addr, player.port);
                    } catch (IOException e) {
                        logger.error("Failed to notify player {}", player.id, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error notifying players", e);
        }
    }
    
    //создаёт новую игру с этим узлом в роли мастера
    public void startNewGame(String playerName, GameConfig config) {
        if (currentState != GameState.IDLE) {
            logger.warn("Cannot start new game: already in game");
            return;
        }
        
        //проверяет не существует ли уже игра с таким именем мастера
        for (GameInfo info : availableGames.values()) {
            for (GamePlayer p : info.players) {
                if (p.getRole() == NodeRole.MASTER && p.getName().equals(playerName)) {
                    logger.warn("Game with master name '{}' already exists", playerName);
                    if (ui != null) {
                        ui.onError("Игра с таким именем мастера уже существует. Измените имя.");
                    }
                    return;
                }
            }
        }
        
        logger.info("Starting new game: {}x{}, food_static={}, state_delay={}ms",
                config.getWidth(), config.getHeight(), 
                config.getFoodStatic(), config.getStateDelayMs());
        
        this.myPlayerName = playerName;
        this.myPlayerId = 0;
        this.myRole = NodeRole.MASTER;
        this.currentState = GameState.IN_GAME;
        this.gameMasterName = playerName;
        this.currentGameName = playerName + "'s game";
        
        gameEngine = new GameEngine(config);
        
        PlayerInfo player = new PlayerInfo(playerName, myPlayerId, NodeRole.MASTER);
        gameEngine.getField().addPlayer(player);
        
        //размещает начальную змею мастера
        if (!gameEngine.placeNewSnake(myPlayerId)) {
            logger.error("Failed to place initial snake");
            stopGame();
            return;
        }
        
        gameEngine.nextTurn();
        
        startMasterTasks(config.getStateDelayMs());
        
        logger.info("Started new game as MASTER with player ID {}", myPlayerId);
        if (ui != null) {
            ui.onGameStarted();
        }
    }
    
    //присоединяется к существующей игре
    public void joinGame(String playerName, String gameName, NodeRole requestedRole) {
        if (currentState != GameState.IDLE) {
            logger.warn("Cannot join game: already in game");
            return;
        }
        
        GameInfo gameInfo = availableGames.get(gameName);
        if (gameInfo == null) {
            logger.warn("Game not found: {}", gameName);
            if (ui != null) {
                ui.onError("Game not found");
            }
            return;
        }
        
        this.myPlayerName = playerName;
        this.masterPort = gameInfo.masterPort;
        this.myRole = requestedRole;
        this.currentState = requestedRole == NodeRole.VIEWER ? GameState.VIEWING : GameState.IN_GAME;
        this.currentGameName = gameName;
        this.myPlayerId = -1;
        
        //находит имя мастера игры
        for (GamePlayer p : gameInfo.players) {
            if (p.getRole() == NodeRole.MASTER) {
                this.gameMasterName = p.getName();
                break;
            }
        }
        
        this.masterAddress = gameInfo.masterAddress;
        this.masterPort = gameInfo.masterPort;
        if (networkManager != null && this.masterAddress != null && this.masterPort > 0) {
            networkManager.updateMasterAddress(this.masterAddress, this.masterPort);
        }
        
        gameEngine = new GameEngine(gameInfo.config);
        
        try {
            GameMessage joinMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setJoin(GameMessage.JoinMsg.newBuilder()
                            .setPlayerName(playerName)
                            .setGameName(gameName)
                            .setRequestedRole(requestedRole)
                            .build())
                    .build();
            
            networkManager.sendUnicast(joinMsg, masterAddress, masterPort);
            
            logger.info("Sent join request to game: {} at {}:{}", gameName, 
                masterAddress.getHostAddress(), masterPort);
            
            if (ui != null) {
                ui.onGameStarted();
            }
        } catch (Exception e) {
            logger.error("Failed to send join request", e);
            if (ui != null) {
                ui.onError("Failed to connect to game: " + e.getMessage());
            }
            stopGame();
        }
    }
    
    //обрабатывает принятие запроса на присоединение к игре
    public void handleJoinAccepted(int playerId) {
        if (this.myPlayerId != -1) {
            logger.debug("Ignoring join accepted, already have ID: {}", this.myPlayerId);
            return;
        }
        
        this.myPlayerId = playerId;
        
        logger.info("Successfully joined/rejoined game with player ID {}", playerId);
        
        //запрашивает текущее состояние игры у мастера
        if (masterAddress != null) {
            try {
                GameMessage discover = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setDiscover(GameMessage.DiscoverMsg.newBuilder().build())
                        .build();
                networkManager.sendUnicast(discover, masterAddress, masterPort);
                logger.info("Sent Discover to master {}:{} to request state", masterAddress.getHostAddress(), masterPort);
            } catch (Exception e) {
                logger.error("Failed to send Discover after join ACK", e);
            }
        }
    }
    
    //отправляет команду изменения направления движения змеи
    public void sendSteer(Direction direction) {
        if (currentState != GameState.IN_GAME || myRole == NodeRole.VIEWER) {
            return;
        }
        
        if (myPlayerId < 0) {
            logger.debug("Cannot send steer: no player ID yet");
            return;
        }
        
        logger.debug("sendSteer: direction={}, myPlayerId={}, myRole={}", direction, myPlayerId, myRole);
        
        //мастер применяет команду локально
        if (myRole == NodeRole.MASTER) {
            if (gameEngine != null) {
                gameEngine.setPlayerMove(myPlayerId, direction);
            }
            return;
        }
        
        //обычный игрок отправляет команду мастеру
        if (masterAddress == null) {
            logger.warn("Cannot send steer: no master address");
            return;
        }
        
        try {
            GameMessage steerMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setSenderId(myPlayerId)
                    .setSteer(GameMessage.SteerMsg.newBuilder()
                            .setDirection(direction)
                            .build())
                    .build();
            
            networkManager.sendUnicast(steerMsg, masterAddress, masterPort);
        } catch (IOException e) {
            logger.error("Failed to send steer message", e);
        }
    }
    
    //выход из игры
    public void leaveGame() {
        if (currentState == GameState.IDLE) {
            return;
        }
        
        logger.info("Leaving game, myRole={}, myPlayerId={}", myRole, myPlayerId);
        
        //обычный игрок уведомляет мастера о выходе
        if (myRole != NodeRole.MASTER && masterAddress != null && myPlayerId >= 0) {
            try {
                GameMessage roleChangeMsg = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setSenderId(myPlayerId)
                        .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(NodeRole.VIEWER)
                                .build())
                        .build();
                
                networkManager.sendUnicast(roleChangeMsg, masterAddress, masterPort);
                logger.info("Sent RoleChange to MASTER at {}:{}", masterAddress.getHostAddress(), masterPort);
            } catch (Exception e) {
                logger.error("Error sending RoleChange", e);
            }
        }
        
        //мастер уведомляет всех игроков о своём выходе
        if (myRole == NodeRole.MASTER && gameEngine != null) {
            try {
                GameMessage roleChangeMsg = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setSenderId(myPlayerId)
                        .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(NodeRole.VIEWER)
                                .build())
                        .build();
                
                for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                    if (player.id != myPlayerId && player.ipAddress != null) {
                        try {
                            InetAddress addr = InetAddress.getByName(player.ipAddress);
                            networkManager.sendUnicast(roleChangeMsg, addr, player.port);
                        } catch (IOException e) {
                            logger.error("Failed to notify player {}", player.id, e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error during leave game", e);
            }
        }
        
        //мёртвый мастер останавливает задачи но сохраняет состояние для переподключения
        if (myRole == NodeRole.MASTER && isMySnakeDead()) {
            logger.info("Leaving to menu as dead MASTER - preserving game state for rejoin");
            if (gameLoopTask != null) {
                gameLoopTask.cancel(false);
                gameLoopTask = null;
            }
            if (announcementTask != null) {
                announcementTask.cancel(false);
                announcementTask = null;
            }
            currentState = GameState.VIEWING;
            if (ui != null) {
                ui.onGameEnded();
            }
        } else {
            stopGame();
        }
        logger.info("Game left successfully");
    }
    
    //выходит из игры без уведомления других игроков
    public void leaveGameSilently() {
        if (currentState == GameState.IDLE) {
            return;
        }
        
        logger.info("Leaving game silently, myRole={}, myPlayerId={}", myRole, myPlayerId);
        
        //отправляет уведомление мастеру если не мастер
        if (myRole != NodeRole.MASTER && masterAddress != null && myPlayerId >= 0) {
            try {
                GameMessage roleChangeMsg = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setSenderId(myPlayerId)
                        .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                .setSenderRole(NodeRole.VIEWER)
                                .build())
                        .build();
                
                networkManager.sendUnicast(roleChangeMsg, masterAddress, masterPort);
            } catch (Exception e) {
                logger.error("Error sending RoleChange", e);
            }
        }
        
        //мастер передаёт власть заместителю
        if (myRole == NodeRole.MASTER && deputyPlayerId >= 0 && deputyAddress != null) {
            try {
                GameMessage roleChangeMsg = GameMessage.newBuilder()
                        .setMsgSeq(networkManager.getNextMsgSeq())
                        .setSenderId(myPlayerId)
                        .setReceiverId(deputyPlayerId)
                        .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                .setReceiverRole(NodeRole.MASTER)
                                .build())
                        .build();
                networkManager.sendUnicast(roleChangeMsg, deputyAddress, deputyPort);
            } catch (Exception e) {
                logger.error("Error notifying deputy", e);
            }
        }
        
        stopGameSilently();
        logger.info("Game left silently");
    }
    
    //останавливает игру и уведомляет ui
    private void stopGame() {
        stopGameSilently();
        if (ui != null) {
            ui.onGameEnded();
        }
    }
    
    //останавливает игру и очищает состояние без уведомления ui
    private void stopGameSilently() {
        currentState = GameState.IDLE;
        myRole = NodeRole.NORMAL;
        myPlayerId = -1;
        masterAddress = null;
        masterPort = 0;
        gameEngine = null;
        gameMasterName = null;
        currentGameName = null;
        deputyPlayerId = -1;
        deputyAddress = null;
        deputyPort = 0;
        
        if (networkManager != null) {
            networkManager.clearPendingMessages();
        }
        
        //останавливает задачи мастера
        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
            gameLoopTask = null;
        }
        if (announcementTask != null) {
            announcementTask.cancel(false);
            announcementTask = null;
        }
        
        logger.info("Game stopped, state reset to IDLE");
    }
    
    //запускает периодические задачи мастера
    private void startMasterTasks(int stateDelayMs) {
        //задача игрового цикла с интервалом stateDelayMs
        gameLoopTask = scheduler.scheduleAtFixedRate(
                this::masterGameLoop,
                stateDelayMs,
                stateDelayMs,
                TimeUnit.MILLISECONDS
        );
        
        //задача отправки объявлений каждую секунду
        announcementTask = scheduler.scheduleAtFixedRate(
                this::sendAnnouncement,
                0,
                1000,
                TimeUnit.MILLISECONDS
        );
    }
    
    //основной игровой цикл мастера выполняющий ход и рассылающий состояние
    private void masterGameLoop() {
        if (gameEngine == null || myRole != NodeRole.MASTER) {
            return;
        }
        
        try {
            gameEngine.nextTurn();
            
            //проверяет умер ли мастер
            var mySnake = gameEngine.getField().getSnakes().get(myPlayerId);
            boolean masterDied = (mySnake == null || 
                mySnake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE);
            
            //проверяет умер ли заместитель
            boolean deputyDied = false;
            if (deputyPlayerId >= 0) {
                var deputySnake = gameEngine.getField().getSnakes().get(deputyPlayerId);
                deputyDied = (deputySnake == null || 
                    deputySnake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ZOMBIE);
            }
            
            //если оба мёртвы проверяет есть ли живые игроки
            if (masterDied && (deputyPlayerId < 0 || deputyDied)) {
                logger.warn("Master and deputy both died, checking for alive players");
                
                boolean hasAlivePlayers = false;
                for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                    var snake = gameEngine.getField().getSnakes().get(player.id);
                    if (snake != null && 
                        snake.state == me.ippolitov.fit.snakes.SnakesProto.GameState.Snake.SnakeState.ALIVE) {
                        hasAlivePlayers = true;
                        break;
                    }
                }
                
                if (!hasAlivePlayers) {
                    logger.info("No alive players remain - master can rejoin");
                    currentState = GameState.VIEWING;
                    broadcastGameState();
                    if (ui != null) {
                        ui.onGameStateUpdated();
                    }
                    return;
                }
                
                //если заместитель тоже мёртв выбирает нового
                if (deputyDied || deputyPlayerId < 0) {
                    selectNewDeputy();
                }
            } else if (masterDied) {
                logger.info("Master died, promoting deputy to MASTER");
                
                //передаёт власть заместителю
                if (deputyPlayerId >= 0) {
                    PlayerInfo deputy = gameEngine.getField().getPlayer(deputyPlayerId);
                    if (deputy != null && deputy.ipAddress != null) {
                        try {
                            InetAddress addr = InetAddress.getByName(deputy.ipAddress);
                            GameMessage roleChangeMsg = GameMessage.newBuilder()
                                    .setMsgSeq(networkManager.getNextMsgSeq())
                                    .setSenderId(myPlayerId)
                                    .setReceiverId(deputyPlayerId)
                                    .setRoleChange(GameMessage.RoleChangeMsg.newBuilder()
                                            .setReceiverRole(NodeRole.MASTER)
                                            .build())
                                    .build();
                            networkManager.sendUnicast(roleChangeMsg, addr, deputy.port);
                            
                            demoteToViewer();
                        } catch (Exception e) {
                            logger.error("Failed to promote deputy after master death", e);
                        }
                    }
                }
            } else if (deputyDied && deputyPlayerId >= 0) {
                logger.info("Deputy died, selecting new deputy");
                selectNewDeputy();
            }
            
            broadcastGameState();
            
            if (ui != null) {
                ui.onGameStateUpdated();
            }
        } catch (Exception e) {
            logger.error("Error in master game loop", e);
        }
    }
    
    //рассылает текущее состояние игры всем игрокам
    private void broadcastGameState() {
        if (gameEngine == null) return;
        
        try {
            me.ippolitov.fit.snakes.SnakesProto.GameState state = gameEngine.buildGameState();
            
            GameMessage stateMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setState(GameMessage.StateMsg.newBuilder()
                            .setState(state)
                            .build())
                    .build();
            
            //отправляет состояние каждому игроку
            for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                if (player.id != myPlayerId && player.ipAddress != null) {
                    try {
                        InetAddress addr = InetAddress.getByName(player.ipAddress);
                        networkManager.sendUnicast(stateMsg, addr, player.port);
                    } catch (IOException e) {
                        logger.error("Failed to send state to player {}", player.id, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error broadcasting game state", e);
        }
    }
    
    //отправляет multicast объявление о текущей игре
    private void sendAnnouncement() {
        if (gameEngine == null || myRole != NodeRole.MASTER) {
            return;
        }
        
        try {
            //собирает информацию о всех игроках
            GamePlayers.Builder playersBuilder = GamePlayers.newBuilder();
            for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                GamePlayer.Builder playerBuilder = GamePlayer.newBuilder()
                        .setName(player.name)
                        .setId(player.id)
                        .setRole(player.role)
                        .setScore(player.score);
                
                //для мастера указывает локальный адрес и порт
                if (player.id == myPlayerId) {
                    playerBuilder.setPort(networkManager.getUnicastPort());
                    try {
                        String localAddr = InetAddress.getLocalHost().getHostAddress();
                        playerBuilder.setIpAddress(localAddr);
                    } catch (Exception ignored) {
                    }
                } else {
                    if (player.ipAddress != null) {
                        playerBuilder.setIpAddress(player.ipAddress);
                    }
                    if (player.port > 0) {
                        playerBuilder.setPort(player.port);
                    }
                }
                
                playersBuilder.addPlayers(playerBuilder.build());
            }
            
            //вычисляет занятые клетки для определения возможности присоединения
            int occupiedCells = 0;
            for (var snake : gameEngine.getField().getSnakes().values()) {
                occupiedCells += snake.getAllCells(
                    gameEngine.getField().getWidth(), 
                    gameEngine.getField().getHeight()
                ).size();
            }
            
            int totalCells = gameEngine.getField().getWidth() * gameEngine.getField().getHeight();
            boolean canJoin = (totalCells - occupiedCells) > 25;
            
            String gameName = currentGameName != null ? currentGameName : myPlayerName + "'s game";
            
            GameAnnouncement announcement = GameAnnouncement.newBuilder()
                    .setPlayers(playersBuilder.build())
                    .setConfig(gameEngine.getConfig())
                    .setCanJoin(canJoin)
                    .setGameName(gameName)
                    .build();
            
            GameMessage announcementMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setAnnouncement(GameMessage.AnnouncementMsg.newBuilder()
                            .addGames(announcement)
                            .build())
                    .build();
            
            networkManager.sendMulticast(announcementMsg);
            logger.info("Sent announcement: game='{}', players={}, unicastPort={}", 
                gameName, gameEngine.getField().getPlayers().size(),
                networkManager.getUnicastPort());
        } catch (IOException e) {
            logger.error("Failed to send announcement", e);
        }
    }
    
    //отправляет объявление конкретному адресату в ответ на discover
    public void sendAnnouncementTo(InetAddress address, int port) {
        if (gameEngine == null || myRole != NodeRole.MASTER) {
            return;
        }
        
        try {
            GamePlayers.Builder playersBuilder = GamePlayers.newBuilder();
            for (PlayerInfo player : gameEngine.getField().getPlayers().values()) {
                GamePlayer.Builder playerBuilder = GamePlayer.newBuilder()
                        .setName(player.name)
                        .setId(player.id)
                        .setRole(player.role)
                        .setScore(player.score);
                
                if (player.id == myPlayerId) {
                    playerBuilder.setPort(networkManager.getUnicastPort());
                    try {
                        String localAddr = InetAddress.getLocalHost().getHostAddress();
                        playerBuilder.setIpAddress(localAddr);
                    } catch (Exception ignored) {
                    }
                } else {
                    if (player.ipAddress != null) {
                        playerBuilder.setIpAddress(player.ipAddress);
                    }
                    if (player.port > 0) {
                        playerBuilder.setPort(player.port);
                    }
                }
                
                playersBuilder.addPlayers(playerBuilder.build());
            }
            
            int occupiedCells = 0;
            for (var snake : gameEngine.getField().getSnakes().values()) {
                occupiedCells += snake.getAllCells(
                    gameEngine.getField().getWidth(), 
                    gameEngine.getField().getHeight()
                ).size();
            }
            
            int totalCells = gameEngine.getField().getWidth() * gameEngine.getField().getHeight();
            boolean canJoin = (totalCells - occupiedCells) > 25;
            
            String gameName = currentGameName != null ? currentGameName : myPlayerName + "'s game";
            
            GameAnnouncement announcement = GameAnnouncement.newBuilder()
                    .setPlayers(playersBuilder.build())
                    .setConfig(gameEngine.getConfig())
                    .setCanJoin(canJoin)
                    .setGameName(gameName)
                    .build();
            
            GameMessage announcementMsg = GameMessage.newBuilder()
                    .setMsgSeq(networkManager.getNextMsgSeq())
                    .setAnnouncement(GameMessage.AnnouncementMsg.newBuilder()
                            .addGames(announcement)
                            .build())
                    .build();
            
            networkManager.sendUnicast(announcementMsg, address, port);
            logger.info("Sent announcement response to {}:{}", address.getHostAddress(), port);
        } catch (IOException e) {
            logger.error("Failed to send announcement response", e);
        }
    }
    

    //завершает работу контроллера останавливая все задачи и освобождая ресурсы
    public void shutdown() {
        leaveGame();
        scheduler.shutdown();
        if (networkManager != null) {
            networkManager.close();
        }
    }
}
