package org.example;

import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DNSResolver {
    //udp канал для отправки dns запросов и получения ответов
    private final DatagramChannel dnsChannel;
    //адрес dns сервера куда отправляются запросы
    private final InetSocketAddress dnsServer;
    //хранит ожидающие ответа dns запросы по id запроса
    private final Map<Integer, PendingDNSRequest> pendingRequests = new HashMap<>();
    //буфер для приема dns ответов размером 512 байт
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(512);

    //создает dns резолвер и регистрирует udp канал в селекторе
    public DNSResolver(Selector selector) throws IOException {
        //открывает udp канал для dns запросов
        dnsChannel = DatagramChannel.open();
        //переводит канал в неблокирующий режим
        dnsChannel.configureBlocking(false);
        //регистрирует канал в селекторе для чтения ответов
        dnsChannel.register(selector, SelectionKey.OP_READ, this);

        //получает список dns серверов из системной конфигурации
        List<InetSocketAddress> servers = ResolverConfig.getCurrentConfig().servers();
        if (servers == null || servers.isEmpty()) {
            //использует google dns если системный не найден
            dnsServer = new InetSocketAddress("8.8.8.8", 53);
        } else {
            //берет первый dns сервер из списка
            dnsServer = servers.get(0);
        }
    }

    //отправляет асинхронный dns запрос для резолва доменного имени
    public void resolve(String hostname, ClientConnection callback) {
        try {
            //формирует dns имя добавляя точку в конец
            Name name = Name.fromString(hostname + ".");
            //создает dns запрос типа A для получения ipv4 адреса
            org.xbill.DNS.Record question = org.xbill.DNS.Record.newRecord(name, Type.A, DClass.IN);
            //создает dns сообщение с запросом
            Message query = Message.newQuery(question);
            //получает id запроса для сопоставления с ответом
            int id = query.getHeader().getID();

            //сохраняет запрос в map для последующей обработки ответа
            pendingRequests.put(id, new PendingDNSRequest(callback, hostname));

            //сериализует dns запрос в байты
            byte[] queryData = query.toWire();
            ByteBuffer buffer = ByteBuffer.wrap(queryData);
            //отправляет udp пакет с dns запросом на dns сервер
            dnsChannel.send(buffer, dnsServer);
        } catch (Exception e) {
            //при ошибке формирования запроса уведомляет клиента о неудаче
            System.out.println("DNS query error for " + hostname + ": " + e.getMessage());
            callback.onDNSResolved(null);
        }
    }

    //обрабатывает полученный dns ответ от сервера
    public void handleDNSResponse(SelectionKey key) {
        try {
            //очищает буфер для приема новых данных
            receiveBuffer.clear();
            //читает udp пакет с dns ответом в буфер
            dnsChannel.receive(receiveBuffer);
            //переводит буфер в режим чтения
            receiveBuffer.flip();

            //копирует данные из буфера в массив байтов
            byte[] data = new byte[receiveBuffer.remaining()];
            receiveBuffer.get(data);

            //парсит dns ответ из байтов
            Message response = new Message(data);
            //извлекает id запроса из ответа
            int id = response.getHeader().getID();

            //находит и удаляет соответствующий ожидающий запрос
            PendingDNSRequest request = pendingRequests.remove(id);
            if (request != null) {
                InetAddress address = null;
                //получает секцию с ответами из dns сообщения
                org.xbill.DNS.Record[] answers = response.getSectionArray(Section.ANSWER);
                //ищет A запись с ipv4 адресом в ответах
                for (org.xbill.DNS.Record answer : answers) {
                    if (answer instanceof ARecord) {
                        //извлекает ip адрес из A записи
                        address = ((ARecord) answer).getAddress();
                        break;
                    }
                }
                //передает результат резолва обратно клиентскому соединению
                request.callback.onDNSResolved(address);
            }
        } catch (Exception e) {
            //логирует ошибку парсинга dns ответа
            System.out.println("DNS response error: " + e.getMessage());
        }
    }

    //хранит информацию об ожидающем ответа dns запросе
    private static class PendingDNSRequest {
        //клиентское соединение которому нужен результат
        final ClientConnection callback;
        //доменное имя которое резолвится
        final String hostname;

        PendingDNSRequest(ClientConnection callback, String hostname) {
            this.callback = callback;
            this.hostname = hostname;
        }
    }
}
