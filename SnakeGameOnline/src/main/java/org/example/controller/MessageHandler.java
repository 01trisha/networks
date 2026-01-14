package org.example.controller;

import me.ippolitov.fit.snakes.SnakesProto.*;
import org.example.network.NetworkManager.ReceivedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;

//обрабатывает все входящие сетевые сообщения и делегирует их контроллеру
public class MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);
    
    private final GameController controller;
    
    public MessageHandler(GameController controller) {
        this.controller = controller;
    }
    
    //основной метод обработки входящих сообщений
    public void handleMessage(ReceivedMessage received) {
        if (received == null || received.message == null) {
            return;
        }
        
        GameMessage msg = received.message;
        
        try {
            //определяет тип сообщения и вызывает соответствующий обработчик
            if (msg.hasAnnouncement()) {
                handleAnnouncement(msg, received.address, received.port);
            } else if (msg.hasJoin()) {
                handleJoin(msg, received.address, received.port);
            } else if (msg.hasState()) {
                handleState(msg, received.address, received.port);
            } else if (msg.hasSteer()) {
                handleSteer(msg, received.address, received.port);
            } else if (msg.hasAck()) {
                handleAck(msg, received.address, received.port);
            } else if (msg.hasPing()) {
                handlePing(msg, received.address, received.port);
            } else if (msg.hasError()) {
                handleError(msg, received.address, received.port);
            } else if (msg.hasRoleChange()) {
                handleRoleChange(msg, received.address, received.port);
            } else if (msg.hasDiscover()) {
                handleDiscover(msg, received.address, received.port);
            }
            
            //отправляет подтверждение для всех кроме ack announcement и discover
            if (!msg.hasAck() && !msg.hasAnnouncement() && !msg.hasDiscover()) {
                sendAck(msg.getMsgSeq(), received.address, received.port);
            }
        } catch (Exception e) {
            logger.error("Error handling message", e);
        }
    }
    
    //обрабатывает объявление о доступной игре
    private void handleAnnouncement(GameMessage msg, InetAddress address, int port) {
        GameMessage.AnnouncementMsg announcement = msg.getAnnouncement();
        
        for (GameAnnouncement game : announcement.getGamesList()) {
            String gameName = game.getGameName();
            
            //пропускает объявление о текущей игре
            String myGameName = controller.getCurrentGameName();
            if (myGameName != null && myGameName.equals(gameName)) {
                continue;
            }

            //ищет мастера в списке чтобы узнать unicast порт
            int masterPort = port;
            for (GamePlayer player : game.getPlayers().getPlayersList()) {
                if (player.getRole() == NodeRole.MASTER && player.hasPort()) {
                    masterPort = player.getPort();
                    break;
                }
            }
            
            GameController.GameInfo existing = controller.getAvailableGames().get(gameName);
            
            //обновляет существующую информацию об игре
            if (existing != null) {
                existing.update(game.getPlayers().getPlayersList(), game.getCanJoin());
                try {
                    String masterIp = null;
                    for (GamePlayer p : game.getPlayers().getPlayersList()) {
                        if (p.getRole() == NodeRole.MASTER && p.hasIpAddress()) {
                            masterIp = p.getIpAddress();
                            break;
                        }
                    }
                    if (masterIp != null) {
                        existing.masterAddress = InetAddress.getByName(masterIp);
                    } else {
                        existing.masterAddress = address;
                    }
                } catch (Exception e) {
                    existing.masterAddress = address;
                }
                existing.masterPort = masterPort;
            } else {
                //создаёт новую запись об игре
                InetAddress masterAddr = address;
                try {
                    for (GamePlayer p : game.getPlayers().getPlayersList()) {
                        if (p.getRole() == NodeRole.MASTER && p.hasIpAddress()) {
                            masterAddr = InetAddress.getByName(p.getIpAddress());
                            break;
                        }
                    }
                } catch (Exception ignored) {}

                GameController.GameInfo gameInfo = new GameController.GameInfo(
                    gameName,
                    game.getConfig(),
                    game.getPlayers().getPlayersList(),
                    game.getCanJoin(),
                    masterAddr,
                    masterPort
                );
                controller.addAvailableGame(gameName, gameInfo);
                logger.info("Added new available game: {}", gameName);
            }
        }
    }

    //обрабатывает запрос на присоединение к игре
    private void handleJoin(GameMessage msg, InetAddress address, int port) {
        logger.info("Received JOIN request from {}:{}", address.getHostAddress(), port);

        if (controller.getMyRole() != NodeRole.MASTER) {
            logger.warn("Received JOIN but not MASTER, my role is {}", controller.getMyRole());
            return;
        }

        GameMessage.JoinMsg join = msg.getJoin();
        String playerName = join.getPlayerName();
        NodeRole requestedRole = join.getRequestedRole();

        logger.info("Processing JOIN: player='{}', requestedRole={}", playerName, requestedRole);

        int newPlayerId = controller.getNextPlayerId();

        //для обычного игрока пытается разместить змею
        boolean success = true;
        if (requestedRole == NodeRole.NORMAL) {
            success = controller.getGameEngine().placeNewSnake(newPlayerId);
            logger.info("Place new snake result: {}", success);
        }

        if (!success) {
            controller.sendError("No space on field for new snake", address, port);
            return;
        }

        controller.addPlayer(newPlayerId, playerName, requestedRole, address.getHostAddress(), port);

        //если это первый обычный игрок делает его заместителем
        if (requestedRole == NodeRole.NORMAL && controller.getDeputyPlayerId() == -1) {
            controller.setDeputy(newPlayerId, address, port);
        }

        controller.sendAck(msg.getMsgSeq(), newPlayerId, address, port);

        logger.info("Player '{}' joined as {} with ID {}", playerName, requestedRole, newPlayerId);
    }
    
    //обрабатывает обновление состояния игры от мастера
    private void handleState(GameMessage msg, InetAddress address, int port) {
        if (controller.getMyRole() == NodeRole.MASTER) {
            return;
        }
        
        //проверяет что состояние пришло от правильного мастера
        InetAddress expectedMaster = controller.getMasterAddress();
        int expectedPort = controller.getMasterPort();
        if (expectedMaster == null || expectedPort <= 0) {
            logger.debug("Ignoring state: no master address set");
            return;
        }
        if (!address.getHostAddress().equals(expectedMaster.getHostAddress()) || port != expectedPort) {
            logger.debug("Ignoring state from wrong master {}:{}, expected {}:{}", 
                address.getHostAddress(), port, expectedMaster.getHostAddress(), expectedPort);
            return;
        }
        
        GameMessage.StateMsg stateMsg = msg.getState();
        me.ippolitov.fit.snakes.SnakesProto.GameState state = stateMsg.getState();
        
        if (controller.getGameEngine() == null) {
            logger.warn("Received state but no game engine");
            return;
        }
        
        NodeRole myActualRole = controller.getMyRole();
        int myPlayerId = controller.getMyPlayerId();
        
        //применяет полученное состояние к локальному движку
        controller.getGameEngine().applyGameState(state);
        
        //восстанавливает свою роль если она была изменена
        if (myPlayerId >= 0) {
            controller.getGameEngine().updatePlayerRole(myPlayerId, myActualRole);
        }
        
        if (controller.getUI() != null) {
            controller.getUI().onGameStateUpdated();
        }
        
        logger.debug("Applied game state order {}", state.getStateOrder());
    }
    
    //обрабатывает команду изменения направления движения
    private void handleSteer(GameMessage msg, InetAddress address, int port) {
        if (controller.getMyRole() != NodeRole.MASTER) {
            logger.warn("Received STEER but not MASTER");
            return;
        }
        
        if (!msg.hasSenderId()) {
            logger.warn("STEER message without sender ID");
            return;
        }
        
        int senderId = msg.getSenderId();
        Direction direction = msg.getSteer().getDirection();
        
        if (controller.getGameEngine() != null) {
            controller.getGameEngine().setPlayerMove(senderId, direction);
            logger.debug("Player {} steered {}", senderId, direction);
        }
    }
    
    private void handleAck(GameMessage msg, InetAddress address, int port) {
        long msgSeq = msg.getMsgSeq();
        controller.getNetworkManager().handleAck(msgSeq);
        
        //при join мастер отправляет ack с receiver_id = новый playerId
        if (msg.hasReceiverId() && controller.getMyPlayerId() == -1) {
            int assignedId = msg.getReceiverId();
            controller.handleJoinAccepted(assignedId);
        }
        
        logger.debug("Received ACK for seq {}", msgSeq);
    }
    
    private void handlePing(GameMessage msg, InetAddress address, int port) {
        logger.debug("Received PING from {}:{}", address.getHostAddress(), port);
    }
    
    private void handleError(GameMessage msg, InetAddress address, int port) {
        String errorMessage = msg.getError().getErrorMessage();
        logger.error("Received ERROR: {}", errorMessage);
        
        if (controller.getUI() != null) {
            controller.getUI().onError(errorMessage);
        }
    }
    
    private void handleRoleChange(GameMessage msg, InetAddress address, int port) {
        GameMessage.RoleChangeMsg roleChange = msg.getRoleChange();
        
        if (roleChange.hasSenderRole()) {
            NodeRole senderRole = roleChange.getSenderRole();
            int senderId = msg.getSenderId();
            
            logger.info("Received RoleChange from player {}: new role={}", senderId, senderRole);
            
            if (senderRole == NodeRole.VIEWER) {
                controller.handlePlayerLeft(senderId);
                logger.info("Player {} left the game (became VIEWER)", senderId);
            } else if (senderRole == NodeRole.MASTER) {
                controller.handleMasterChange(address, port);
                logger.info("New MASTER at {}:{}", address.getHostAddress(), port);
            }
        }
        
        if (roleChange.hasReceiverRole()) {
            NodeRole receiverRole = roleChange.getReceiverRole();
            
            if (receiverRole == NodeRole.DEPUTY) {
                controller.setMyRoleDeputy(address, port);
                logger.info("I am now DEPUTY");
            } else if (receiverRole == NodeRole.MASTER) {
                controller.promoteToMaster();
                logger.info("I am now MASTER (promoted from DEPUTY)");
            } else if (receiverRole == NodeRole.VIEWER) {
                controller.demoteToViewer();
                logger.info("I am now VIEWER");
            }
        }
    }
    
    //обрабатывает запрос информации об игре
    private void handleDiscover(GameMessage msg, InetAddress address, int port) {
        if (controller.getMyRole() != NodeRole.MASTER) {
            return;
        }
        
        logger.info("Received DISCOVER from {}:{}, sending announcement", address.getHostAddress(), port);
        controller.sendAnnouncementTo(address, port);
    }
    
    //отправляет подтверждение получения сообщения
    private void sendAck(long msgSeq, InetAddress address, int port) {
        controller.sendAck(msgSeq, controller.getMyPlayerId(), address, port);
    }
}
