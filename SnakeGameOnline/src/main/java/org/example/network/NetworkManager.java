package org.example.network;

import me.ippolitov.fit.snakes.SnakesProto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

//управляет сетевым взаимодействием через udp unicast и multicast
public class NetworkManager {
    private static final Logger logger = LoggerFactory.getLogger(NetworkManager.class);
    
    private static final String MULTICAST_ADDRESS = "239.192.0.4"; //адрес multicast группы
    private static final int MULTICAST_PORT = 9192; //порт multicast группы
    
    private final DatagramSocket unicastSocket; //сокет для unicast сообщений
    private final MulticastSocket multicastSocket; //сокет для multicast сообщений
    private final InetAddress multicastGroup; //адрес multicast группы
    
    private final AtomicLong msgSeq = new AtomicLong(0); //счётчик порядковых номеров сообщений
    private final ConcurrentHashMap<Long, PendingMessage> pendingAcks = new ConcurrentHashMap<>(); //ожидающие подтверждения сообщения
    private final ConcurrentHashMap<String, Long> lastMessageTime = new ConcurrentHashMap<>(); //время последнего сообщения от узлов
    
    private volatile boolean running = true;
    
    //хранит информацию о сообщении ожидающем подтверждения
    public static class PendingMessage {
        public GameMessage message; //само сообщение
        public InetAddress address; //адрес получателя
        public int port; //порт получателя
        public long sentTime; //время последней отправки
        public int retries; //количество попыток повторной отправки
        
        //создаёт запись о неподтверждённом сообщении
        public PendingMessage(GameMessage message, InetAddress address, int port) {
            this.message = message;
            this.address = address;
            this.port = port;
            this.sentTime = System.currentTimeMillis();
            this.retries = 0;
        }
    }
    
    //инициализирует сетевые сокеты и присоединяется к multicast группе
    public NetworkManager() throws IOException {
        unicastSocket = new DatagramSocket();
        
        multicastSocket = new MulticastSocket(null);
        multicastSocket.setReuseAddress(true);
        multicastSocket.bind(new InetSocketAddress(MULTICAST_PORT));
        
        multicastGroup = InetAddress.getByName(MULTICAST_ADDRESS);
        
        //включаем loopback чтобы видеть свои же multicast
        multicastSocket.setLoopbackMode(false);
        
        //присоединяемся к группе на всех интерфейсах включая loopback
        var interfaces = NetworkInterface.getNetworkInterfaces();
        boolean joinedAny = false;
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isUp() && ni.supportsMulticast()) {
                try {
                    multicastSocket.joinGroup(new InetSocketAddress(multicastGroup, MULTICAST_PORT), ni);
                    logger.info("Joined multicast group on interface: {}", ni.getName());
                    joinedAny = true;
                } catch (Exception e) {
                    logger.debug("Failed to join multicast on {}: {}", ni.getName(), e.getMessage());
                }
            }
        }
        
        if (!joinedAny) {
            throw new IOException("Could not join multicast group on any interface");
        }
        
        logger.info("NetworkManager started: unicast port={}, multicast={}:{}",
                unicastSocket.getLocalPort(), MULTICAST_ADDRESS, MULTICAST_PORT);
    }
    
    //возвращает порт unicast сокета
    public int getUnicastPort() {
        return unicastSocket.getLocalPort();
    }
    
    //генерирует следующий порядковый номер сообщения
    public long getNextMsgSeq() {
        return msgSeq.incrementAndGet();
    }
    
    //отправляет unicast сообщение конкретному адресату и отслеживает подтверждение
    public void sendUnicast(GameMessage message, InetAddress address, int port) throws IOException {
        byte[] data = message.toByteArray();
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        unicastSocket.send(packet);
        
        //добавляет в очередь ожидания подтверждения если это не ack или announcement
        if (!isAckOrAnnouncement(message)) {
            pendingAcks.put(message.getMsgSeq(), new PendingMessage(message, address, port));
        }
        
        updateLastMessageTime(address.getHostAddress() + ":" + port);
    }
    
    //отправляет multicast сообщение всей группе
    public void sendMulticast(GameMessage message) throws IOException {
        byte[] data = message.toByteArray();
        DatagramPacket packet = new DatagramPacket(data, data.length, multicastGroup, MULTICAST_PORT);
        multicastSocket.send(packet);
        logger.debug("Sent multicast to {}:{}, {} bytes", MULTICAST_ADDRESS, MULTICAST_PORT, data.length);
    }
    
    //принимает unicast сообщение с заданным таймаутом
    public ReceivedMessage receiveUnicast(int timeout) throws IOException {
        byte[] buffer = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        
        unicastSocket.setSoTimeout(timeout);
        try {
            unicastSocket.receive(packet);
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
            GameMessage message = GameMessage.parseFrom(data);
            
            updateLastMessageTime(packet.getAddress().getHostAddress() + ":" + packet.getPort());
            
            return new ReceivedMessage(message, packet.getAddress(), packet.getPort());
        } catch (SocketTimeoutException e) {
            return null;
        }
    }
    
    //принимает multicast сообщение с заданным таймаутом
    public ReceivedMessage receiveMulticast(int timeout) throws IOException {
        byte[] buffer = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        
        multicastSocket.setSoTimeout(timeout);
        try {
            multicastSocket.receive(packet);
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
            GameMessage message = GameMessage.parseFrom(data);
            return new ReceivedMessage(message, packet.getAddress(), packet.getPort());
        } catch (SocketTimeoutException e) {
            return null;
        }
    }
    
    //обрабатывает подтверждение получения сообщения
    public void handleAck(long msgSeq) {
        pendingAcks.remove(msgSeq);
    }
    
    //повторно отправляет неподтверждённые сообщения через заданный интервал
    public void retransmitPending(long retransmitInterval) throws IOException {
        long now = System.currentTimeMillis();
        
        for (PendingMessage pm : pendingAcks.values()) {
            if (now - pm.sentTime > retransmitInterval) {
                byte[] data = pm.message.toByteArray();
                DatagramPacket packet = new DatagramPacket(data, data.length, pm.address, pm.port);
                unicastSocket.send(packet);
                pm.sentTime = now;
                pm.retries++;
            }
        }
    }
    
    //обновляет адрес мастера для всех ожидающих сообщений
    public void updateMasterAddress(InetAddress newAddress, int newPort) {
        for (PendingMessage pm : pendingAcks.values()) {
            pm.address = newAddress;
            pm.port = newPort;
        }
    }
    
    //обновляет время последнего сообщения от узла
    private void updateLastMessageTime(String key) {
        lastMessageTime.put(key, System.currentTimeMillis());
    }
    
    //возвращает время в миллисекундах с последнего сообщения от узла
    public long getTimeSinceLastMessage(String address, int port) {
        String key = address + ":" + port;
        Long lastTime = lastMessageTime.get(key);
        if (lastTime == null) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastTime;
    }
    
    //удаляет информацию об узле из отслеживания
    public void removeNodeTracking(String address, int port) {
        String key = address + ":" + port;
        lastMessageTime.remove(key);
    }
    
    //очищает все ожидающие подтверждения сообщения и таймеры
    public void clearPendingMessages() {
        pendingAcks.clear();
        lastMessageTime.clear();
        logger.debug("Cleared all pending messages and message timings");
    }
    
    //проверяет является ли сообщение подтверждением или объявлением
    private boolean isAckOrAnnouncement(GameMessage message) {
        return message.hasAck() || message.hasAnnouncement() || message.hasDiscover();
    }
    
    //закрывает сокеты и покидает multicast группу
    public void close() {
        running = false;
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isUp() && ni.supportsMulticast()) {
                    try {
                        multicastSocket.leaveGroup(new InetSocketAddress(multicastGroup, MULTICAST_PORT), ni);
                    } catch (Exception e) {
                    }
                }
            }
        } catch (Exception e) {
        }
        multicastSocket.close();
        unicastSocket.close();
        logger.info("NetworkManager closed");
    }
    
    //хранит полученное сообщение с адресом отправителя
    public static class ReceivedMessage {
        public final GameMessage message; //полученное сообщение
        public final InetAddress address; //адрес отправителя
        public final int port; //порт отправителя
        
        public ReceivedMessage(GameMessage message, InetAddress address, int port) {
            this.message = message;
            this.address = address;
            this.port = port;
        }
    }
}
