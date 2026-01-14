package org.example;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class ClientConnection {
    private static final int BUFFER_SIZE = 8192;
    private static final byte SOCKS_VERSION = 0x05;
    private static final byte NO_AUTH = 0x00;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte REP_SUCCESS = 0x00;
    private static final byte REP_GENERAL_FAILURE = 0x01;
    private static final byte REP_HOST_UNREACHABLE = 0x04;
    private static final byte REP_CONNECTION_REFUSED = 0x05;

    private enum State {
        GREETING, REQUEST, CONNECTING, DNS_RESOLVING, TUNNELING, CLOSED
    }

    private final SocketChannel clientChannel;
    private SocketChannel remoteChannel;
    private final Selector selector;
    private final DNSResolver dnsResolver;

    //текущее состояние обработки
    private State state = State.GREETING;
    
    //буфер для приема socks5 команд от клиента
    private final ByteBuffer inputBuffer = ByteBuffer.allocate(512);
    //буфер для отправки socks5 ответов клиенту
    private final ByteBuffer outputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    //буфер для передачи данных от клиента к удаленному серверу
    private final ByteBuffer clientToRemoteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    //буфер для передачи данных от удаленного сервера к клиенту
    private final ByteBuffer remoteToClientBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    
    //порт целевого сервера
    private int targetPort;
    //хост целевого сервера
    private String targetHost;
    //флаг что клиент закрыл свою сторону соединения
    private boolean clientEof = false;
    //флаг что удаленный сервер закрыл свою сторону соединения
    private boolean remoteEof = false;
    
    //адрес клиента для логирования
    private final String clientAddress;

    //создает новое клиентское соединение
    public ClientConnection(SocketChannel clientChannel, Selector selector, DNSResolver dnsResolver) {
        this.clientChannel = clientChannel;
        this.selector = selector;
        this.dnsResolver = dnsResolver;
        //сохраняет адрес клиента для логирования
        this.clientAddress = clientChannel.socket().getRemoteSocketAddress().toString();
        log("New connection from " + clientAddress);
    }
    
    //выводит лог сообщение с адресом клиента
    private void log(String message) {
        System.out.println("[" + clientAddress + "] " + message);
    }

    //обрабатывает событие готовности канала к чтению
    public void handleRead(SelectionKey key) throws IOException {
        //определяет откуда пришли данные и вызывает соответствующий обработчик
        if (key.channel() == clientChannel) {
            handleClientRead(key);
        } else if (key.channel() == remoteChannel) {
            handleRemoteRead(key);
        }
    }

    //обрабатывает чтение данных от клиента в зависимости от состояния
    private void handleClientRead(SelectionKey key) throws IOException {
        switch (state) {
            case GREETING:
                //читает приветствие socks5 с методами аутентификации
                readGreeting(key);
                break;
            case REQUEST:
                //читает команду connect с целевым адресом
                readRequest(key);
                break;
            case TUNNELING:
                //туннелирует данные от клиента к удаленному серверу
                tunnelClientToRemote(key);
                break;
            default:
                break;
        }
    }

    //обрабатывает чтение данных от удаленного сервера
    private void handleRemoteRead(SelectionKey key) throws IOException {
        if (state == State.TUNNELING) {
            //туннелирует данные от удаленного сервера к клиенту
            tunnelRemoteToClient(key);
        }
    }

    //читает и обрабатывает socks5 приветствие от клиента
    private void readGreeting(SelectionKey key) throws IOException {
        //читает данные из канала клиента в буфер
        int bytesRead = clientChannel.read(inputBuffer);
        if (bytesRead == -1) {
            //клиент закрыл соединение
            close();
            return;
        }
        
        //проверяет что получено минимум 2 байта для заголовка
        if (inputBuffer.position() < 2) {
            return;
        }
        
        //извлекает версию протокола и количество методов аутентификации
        byte version = inputBuffer.get(0);
        int nMethods = inputBuffer.get(1) & 0xFF;
        
        //ждет получения всех байтов приветствия
        if (inputBuffer.position() < 2 + nMethods) {
            return;
        }
        
        //проверяет что версия протокола равна 5
        if (version != SOCKS_VERSION) {
            log("Invalid SOCKS version: " + version);
            close();
            return;
        }
        
        log("SOCKS5 greeting received, auth methods: " + nMethods);
        
        //очищает буфер после обработки приветствия
        inputBuffer.clear();
        
        //формирует ответ клиенту с выбором метода без аутентификации
        outputBuffer.clear();
        outputBuffer.put(SOCKS_VERSION);
        outputBuffer.put(NO_AUTH);
        outputBuffer.flip();
        
        //переходит в состояние ожидания команды
        state = State.REQUEST;
        
        //отправляет ответ клиенту
        clientChannel.write(outputBuffer);
        if (outputBuffer.hasRemaining()) {
            //если не все данные отправлены регистрирует интерес к записи
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    //читает и обрабатывает socks5 запрос connect от клиента
    private void readRequest(SelectionKey key) throws IOException {
        //читает данные команды из канала клиента
        int bytesRead = clientChannel.read(inputBuffer);
        if (bytesRead == -1) {
            close();
            return;
        }
        
        //проверяет что получено минимум 4 байта для заголовка команды
        if (inputBuffer.position() < 4) {
            return;
        }
        
        //извлекает версию команду и тип адреса
        byte version = inputBuffer.get(0);
        byte cmd = inputBuffer.get(1);
        byte atyp = inputBuffer.get(3);
        
        //проверяет что версия и команда корректны
        if (version != SOCKS_VERSION || cmd != CMD_CONNECT) {
            log("Invalid request: version=" + version + ", cmd=" + cmd);
            sendErrorAndClose(REP_GENERAL_FAILURE);
            return;
        }
        
        //обрабатывает ipv4 адрес
        if (atyp == ATYP_IPV4) {
            //ждет получения всех 10 байтов для ipv4 адреса и порта
            if (inputBuffer.position() < 10) {
                return;
            }
            //читает 4 байта ipv4 адреса
            byte[] addr = new byte[4];
            inputBuffer.position(4);
            inputBuffer.get(addr);
            //читает 2 байта порта
            targetPort = inputBuffer.getShort() & 0xFFFF;
            inputBuffer.clear();
            
            try {
                //создает inet адрес из байтов
                InetAddress address = InetAddress.getByAddress(addr);
                targetHost = address.getHostAddress();
                log("CONNECT to " + targetHost + ":" + targetPort);
                //подключается к целевому серверу
                connectToRemote(address);
            } catch (Exception e) {
                log("Failed to connect: " + e.getMessage());
                sendErrorAndClose(REP_HOST_UNREACHABLE);
            }
        } else if (atyp == ATYP_DOMAIN) {
            //обрабатывает доменное имя
            if (inputBuffer.position() < 5) {
                return;
            }
            //читает длину доменного имени
            int domainLen = inputBuffer.get(4) & 0xFF;
            //ждет получения всех байтов для домена и порта
            if (inputBuffer.position() < 7 + domainLen) {
                return;
            }
            
            //читает доменное имя
            byte[] domainBytes = new byte[domainLen];
            inputBuffer.position(5);
            inputBuffer.get(domainBytes);
            targetHost = new String(domainBytes);
            //читает порт
            targetPort = inputBuffer.getShort() & 0xFFFF;
            inputBuffer.clear();
            
            log("CONNECT to " + targetHost + ":" + targetPort + " (resolving DNS...)");
            //переходит в состояние резолвинга dns
            state = State.DNS_RESOLVING;
            //отключает чтение пока идет резолвинг
            key.interestOps(0);
            //запускает асинхронный dns запрос
            dnsResolver.resolve(targetHost, this);
        } else {
            log("Unsupported address type: " + atyp);
            sendErrorAndClose(REP_GENERAL_FAILURE);
        }
    }

    //вызывается dns резолвером когда доменное имя разрешено в ip адрес
    public void onDNSResolved(InetAddress address) {
        try {
            if (address == null) {
                //dns резолвинг не удался
                log("DNS resolution failed for " + targetHost);
                sendErrorAndClose(REP_HOST_UNREACHABLE);
            } else {
                //dns успешно разрешен подключаемся к целевому серверу
                log("DNS resolved: " + targetHost + " -> " + address.getHostAddress());
                connectToRemote(address);
            }
        } catch (IOException e) {
            try {
                close();
            } catch (IOException ex) {

            }
        }
    }

    //открывает неблокирующее соединение с целевым сервером
    private void connectToRemote(InetAddress address) throws IOException {
        //создает канал к удаленному серверу
        remoteChannel = SocketChannel.open();
        //переводит канал в неблокирующий режим
        remoteChannel.configureBlocking(false);
        //инициирует подключение к целевому адресу
        boolean connected = remoteChannel.connect(new InetSocketAddress(address, targetPort));
        
        if (connected) {
            //подключение установлено сразу
            onConnected();
        } else {
            //регистрирует канал для отслеживания завершения подключения
            remoteChannel.register(selector, SelectionKey.OP_CONNECT, this);
            state = State.CONNECTING;
        }
    }

    //обрабатывает завершение неблокирующего подключения к удаленному серверу
    public void handleConnect(SelectionKey key) throws IOException {
        try {
            //завершает процесс подключения
            if (remoteChannel.finishConnect()) {
                onConnected();
            }
        } catch (IOException e) {
            //подключение не удалось
            sendErrorAndClose(REP_CONNECTION_REFUSED);
        }
    }

    //вызывается после успешного подключения к целевому серверу
    private void onConnected() throws IOException {
        log("Connected to " + targetHost + ":" + targetPort);
        
        //формирует успешный ответ socks5 клиенту
        outputBuffer.clear();
        outputBuffer.put(SOCKS_VERSION);
        outputBuffer.put(REP_SUCCESS);
        outputBuffer.put((byte) 0x00);
        outputBuffer.put(ATYP_IPV4);
        outputBuffer.putInt(0);
        outputBuffer.putShort((short) 0);
        outputBuffer.flip();
        
        //переходит в режим туннелирования данных
        state = State.TUNNELING;
        
        //отправляет ответ клиенту
        clientChannel.write(outputBuffer);
        
        //получает ключи селектора для обоих каналов
        SelectionKey clientKey = clientChannel.keyFor(selector);
        SelectionKey remoteKey = remoteChannel.keyFor(selector);
        
        //регистрирует удаленный канал на чтение данных от сервера
        if (remoteKey == null) {
            remoteKey = remoteChannel.register(selector, SelectionKey.OP_READ, this);
        } else {
            remoteKey.interestOps(SelectionKey.OP_READ);
        }
        
        //регистрирует клиентский канал на чтение и возможно запись
        int clientOps = SelectionKey.OP_READ;
        if (outputBuffer.hasRemaining()) {
            clientOps |= SelectionKey.OP_WRITE;
        }
        clientKey.interestOps(clientOps);
    }

    //отправляет ошибку клиенту и закрывает соединение
    private void sendErrorAndClose(byte errorCode) throws IOException {
        //формирует ответ с кодом ошибки
        outputBuffer.clear();
        outputBuffer.put(SOCKS_VERSION);
        outputBuffer.put(errorCode);
        outputBuffer.put((byte) 0x00);
        outputBuffer.put(ATYP_IPV4);
        outputBuffer.putInt(0);
        outputBuffer.putShort((short) 0);
        outputBuffer.flip();
        
        try {
            //пытается отправить ошибку клиенту
            clientChannel.write(outputBuffer);
        } catch (IOException e) {
        }
        //закрывает соединение
        close();
    }

    //туннелирует данные от клиента к удаленному серверу
    private void tunnelClientToRemote(SelectionKey key) throws IOException {
        //читает данные от клиента в буфер
        int bytesRead = clientChannel.read(clientToRemoteBuffer);
        
        if (bytesRead == -1) {
            //клиент закрыл свою сторону соединения
            clientEof = true;
            //отключает дальнейшее чтение от клиента
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            
            if (clientToRemoteBuffer.position() == 0) {
                //буфер пуст можно сразу закрыть запись к серверу
                shutdownOutput(remoteChannel);
                checkCloseComplete();
            } else {
                //в буфере есть данные регистрирует запись к серверу
                SelectionKey remoteKey = remoteChannel.keyFor(selector);
                if (remoteKey != null) {
                    remoteKey.interestOps(remoteKey.interestOps() | SelectionKey.OP_WRITE);
                }
            }
            return;
        }
        
        if (bytesRead > 0) {
            //данные прочитаны регистрирует запись к удаленному серверу
            SelectionKey remoteKey = remoteChannel.keyFor(selector);
            if (remoteKey != null) {
                remoteKey.interestOps(remoteKey.interestOps() | SelectionKey.OP_WRITE);
            }
            
            if (!clientToRemoteBuffer.hasRemaining()) {
                //буфер заполнен приостанавливает чтение от клиента
                key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            }
        }
    }

    //туннелирует данные от удаленного сервера к клиенту
    private void tunnelRemoteToClient(SelectionKey key) throws IOException {
        //читает данные от удаленного сервера в буфер
        int bytesRead = remoteChannel.read(remoteToClientBuffer);
        
        if (bytesRead == -1) {
            //удаленный сервер закрыл свою сторону соединения
            remoteEof = true;
            //отключает дальнейшее чтение от удаленного сервера
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            
            if (remoteToClientBuffer.position() == 0) {
                //буфер пуст можно сразу закрыть запись к клиенту
                shutdownOutput(clientChannel);
                checkCloseComplete();
            } else {
                //в буфере есть данные регистрирует запись к клиенту
                SelectionKey clientKey = clientChannel.keyFor(selector);
                if (clientKey != null) {
                    clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_WRITE);
                }
            }
            return;
        }
        
        if (bytesRead > 0) {
            //данные прочитаны регистрирует запись к клиенту
            SelectionKey clientKey = clientChannel.keyFor(selector);
            if (clientKey != null) {
                clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_WRITE);
            }
            
            if (!remoteToClientBuffer.hasRemaining()) {
                //буфер заполнен приостанавливает чтение от сервера
                key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            }
        }
    }

    //обрабатывает событие готовности канала к записи
    public void handleWrite(SelectionKey key) throws IOException {
        //определяет куда записывать данные и вызывает соответствующий обработчик
        if (key.channel() == clientChannel) {
            handleClientWrite(key);
        } else if (key.channel() == remoteChannel) {
            handleRemoteWrite(key);
        }
    }

    //обрабатывает запись данных к клиенту
    private void handleClientWrite(SelectionKey key) throws IOException {
        //отправляет данные из буфера ответа если они есть
        if (outputBuffer.hasRemaining()) {
            clientChannel.write(outputBuffer);
            if (!outputBuffer.hasRemaining()) {
                outputBuffer.clear();
            }
        }
        
        //отправляет туннелированные данные от сервера к клиенту
        if (state == State.TUNNELING && remoteToClientBuffer.position() > 0) {
            //переводит буфер в режим чтения
            remoteToClientBuffer.flip();
            //записывает данные в канал клиента
            clientChannel.write(remoteToClientBuffer);
            //переводит буфер обратно в режим записи
            remoteToClientBuffer.compact();
            
            //если в буфере есть место возобновляет чтение от сервера
            if (remoteToClientBuffer.hasRemaining() && !remoteEof) {
                SelectionKey remoteKey = remoteChannel.keyFor(selector);
                if (remoteKey != null) {
                    remoteKey.interestOps(remoteKey.interestOps() | SelectionKey.OP_READ);
                }
            }
        }
        
        //если все данные отправлены отключает интерес к записи
        if (!outputBuffer.hasRemaining() && remoteToClientBuffer.position() == 0) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            
            //если сервер закрыл соединение закрывает запись к клиенту
            if (remoteEof) {
                shutdownOutput(clientChannel);
                checkCloseComplete();
            }
        }
    }

    //обрабатывает запись данных к удаленному серверу
    private void handleRemoteWrite(SelectionKey key) throws IOException {
        //отправляет туннелированные данные от клиента к серверу
        if (clientToRemoteBuffer.position() > 0) {
            //переводит буфер в режим чтения
            clientToRemoteBuffer.flip();
            //записывает данные в канал удаленного сервера
            remoteChannel.write(clientToRemoteBuffer);
            //переводит буфер обратно в режим записи
            clientToRemoteBuffer.compact();
            
            //если в буфере есть место возобновляет чтение от клиента
            if (clientToRemoteBuffer.hasRemaining() && !clientEof) {
                SelectionKey clientKey = clientChannel.keyFor(selector);
                if (clientKey != null) {
                    clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_READ);
                }
            }
        }
        
        //если все данные отправлены отключает интерес к записи
        if (clientToRemoteBuffer.position() == 0) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            
            //если клиент закрыл соединение закрывает запись к серверу
            if (clientEof) {
                shutdownOutput(remoteChannel);
                checkCloseComplete();
            }
        }
    }

    //закрывает сторону отправки данных в канале
    private void shutdownOutput(SocketChannel channel) {
        if (channel == null || !channel.isOpen()) return;
        try {
            //вызывает shutdown для корректного завершения отправки
            channel.socket().shutdownOutput();
        } catch (IOException e) {
        }
    }

    //проверяет можно ли полностью закрыть соединение
    private void checkCloseComplete() throws IOException {
        //соединение закрывается только когда обе стороны завершены и буферы пусты
        if (clientEof && remoteEof &&
            clientToRemoteBuffer.position() == 0 && 
            remoteToClientBuffer.position() == 0) {
            close();
        }
    }

    //закрывает оба канала и отменяет регистрацию в селекторе
    public void close() throws IOException {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        log("Connection closed");
        
        //закрывает клиентский канал
        if (clientChannel != null) {
            SelectionKey key = clientChannel.keyFor(selector);
            if (key != null) key.cancel();
            try { clientChannel.close(); } catch (IOException e) { }
        }
        
        //закрывает канал к удаленному серверу
        if (remoteChannel != null) {
            SelectionKey key = remoteChannel.keyFor(selector);
            if (key != null) key.cancel();
            try { remoteChannel.close(); } catch (IOException e) { }
        }
    }
}
