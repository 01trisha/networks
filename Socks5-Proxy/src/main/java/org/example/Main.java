package org.example;

public class Main {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("java -jar Socks5-Proxy.jar <port>");
            System.exit(1);
        }

        try {
            int port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                System.out.println("Port must be between 1 and 65535");
                System.exit(1);
            }

            Socks5ProxyServer server = new Socks5ProxyServer(port);
            server.start();
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + args[0]);
            System.exit(1);
        } catch (Exception e) {
            System.out.println("Failed to start server: " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }
}