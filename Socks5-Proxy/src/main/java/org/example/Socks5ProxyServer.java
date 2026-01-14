package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;

public class Socks5ProxyServer {
    private final int port;
    private Selector selector;
    private DNSResolver dnsResolver;

    public Socks5ProxyServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        selector = Selector.open();
        dnsResolver = new DNSResolver(selector);

        //создает серверный канал для приема входящих соединений
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));

        //регистрирует серверный канал в селекторе на прием соединений
        serverChannel.register(selector, SelectionKey.OP_ACCEPT, null);

        System.out.println("SOCKS5 Proxy Server started on port " + port);

        while (true) {
            selector.select();
            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

            //обрабатывает каждый готовый канал
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();

                //пропускает невалидные ключи отмененных каналов
                if (!key.isValid()) {
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        //серверный канал готов принять новое соединение
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        //канал готов для чтения данных
                        handleRead(key);
                    } else if (key.isWritable()) {
                        //канал готов для записи данных
                        handleWrite(key);
                    } else if (key.isConnectable()) {
                        //неблокирующее подключение завершено
                        handleConnect(key);
                    }
                } catch (Exception e) {
                    closeKey(key);
                }
            }
        }
    }

    //принимает новое входящее клиентское соединение
    private void handleAccept(SelectionKey key) throws IOException {
        //получает серверный канал из ключа
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        //принимает входящее соединение от клиента
        var clientChannel = serverChannel.accept();
        if (clientChannel != null) {
            //переводит клиентский канал в неблокирующий режим
            clientChannel.configureBlocking(false);
            //создает объект для управления этим клиентским соединением
            ClientConnection connection = new ClientConnection(clientChannel, selector, dnsResolver);
            //регистрирует клиентский канал на чтение с привязкой к connection
            clientChannel.register(selector, SelectionKey.OP_READ, connection);
        }
    }

    //обрабатывает событие готовности канала к чтению
    private void handleRead(SelectionKey key) throws IOException {
        //получает объект привязанный к ключу селектора
        Object attachment = key.attachment();
        if (attachment instanceof DNSResolver) {
            //пришел dns ответ от сервера
            ((DNSResolver) attachment).handleDNSResponse(key);
        } else if (attachment instanceof ClientConnection) {
            //данные от клиента или удаленного сервера
            ((ClientConnection) attachment).handleRead(key);
        }
    }

    //обрабатывает событие готовности канала к записи
    private void handleWrite(SelectionKey key) throws IOException {
        //получает объект привязанный к ключу селектора
        Object attachment = key.attachment();
        if (attachment instanceof ClientConnection) {
            //канал готов для отправки данных клиенту или серверу
            ((ClientConnection) attachment).handleWrite(key);
        }
    }

    //обрабатывает завершение неблокирующего подключения
    private void handleConnect(SelectionKey key) throws IOException {
        //получает объект привязанный к ключу селектора
        Object attachment = key.attachment();
        if (attachment instanceof ClientConnection) {
            //подключение к удаленному серверу завершено
            ((ClientConnection) attachment).handleConnect(key);
        }
    }

    //закрывает канал и отменяет его регистрацию при ошибке
    private void closeKey(SelectionKey key) {
        try {
            //получает объект привязанный к ключу
            Object attachment = key.attachment();
            if (attachment instanceof ClientConnection) {
                //закрывает клиентское соединение корректно
                ((ClientConnection) attachment).close();
            }
            //отменяет регистрацию ключа в селекторе
            key.cancel();
            //закрывает сам канал
            key.channel().close();
        } catch (IOException e) {
        }
    }
}
