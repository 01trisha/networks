package org.example;

import java.net.*;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Manager {
    private static final int PORT = 8888;
    private static final long SEND_INTERVAL_MS = 2000;
    private static final long CLEAN_INTERVAL_MS = 5000;
    private static final long MAX_TIME_MS = 10000;
    private static final String HELLO_MESSAGE = "Hello";
    private static final String BYE_MESSAGE = "Bye";

    private final InetAddress multicastGroup;
    private MulticastSocket multicastSocket;
    private final String localAddress;
    private final String processID;

    private final ConcurrentHashMap<String, Long> aliveApps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

    private boolean hasChanges = false;
    private volatile boolean running = false;

    public Manager(String multicastGroupAddress) throws Exception {

        InetAddress inetAddress = InetAddress.getByName(multicastGroupAddress);
        if (!inetAddress.isMulticastAddress()) {
            System.err.println("This address is not a multicast address");
            System.exit(1);
        }

        multicastGroup = inetAddress;
        boolean isIPv6 = multicastGroup instanceof Inet6Address;

        LocalEndpoint endpoint = resolveLocalEndpoint(isIPv6);
        if (endpoint == null) {
            throw new IllegalStateException("No suitable local " + (isIPv6 ? "IPv6" : "IPv4") + " address for multicast");
        }

        multicastSocket = new MulticastSocket(PORT);
        multicastSocket.setNetworkInterface(endpoint.networkInterface);
        multicastSocket.joinGroup(new InetSocketAddress(multicastGroup, PORT), endpoint.networkInterface);

        localAddress = endpoint.address.getHostAddress();
        processID = UUID.randomUUID().toString();

        System.out.println("Multicast group: " + multicastGroupAddress + " is " + (multicastGroup instanceof Inet4Address ? "IPv4" : "IPv6") + " protocol");
        System.out.println("Using interface: " + endpoint.networkInterface.getDisplayName());
        System.out.println("Local address: " + localAddress);
        System.out.println("Process ID: " + processID);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }


    public void start() {

        if (running) {
            return;
        }
        running = true;
        executor.execute(this::receiverTask);

        executor.scheduleAtFixedRate(
                this::senderTask, 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);

        executor.scheduleAtFixedRate(
                this::cleanerTask, 0, CLEAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void senderTask() {
        try {
            if (!running || multicastSocket.isClosed()) {
                return;
            }
            String message = String.join("|", HELLO_MESSAGE, localAddress, processID);
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, multicastGroup, PORT);

            multicastSocket.send(packet);

        } catch (Exception e) {
            System.err.println("Sender error: " + e.getMessage());
        }
    }


    private void receiverTask() {
        byte[] buffer = new byte[1024];

        while (!Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                multicastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength()).trim();

                String[] parts = message.split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }

                String messageType = parts[0];
                String remoteIp = parts[1];
                String remoteProcessID = parts[2];

                if (remoteProcessID.equals(processID)) {
                    continue;
                }

                String appKey = remoteIp + " [" + remoteProcessID + "]";

                if (HELLO_MESSAGE.equals(messageType)) {
                    long currentTime = System.currentTimeMillis();

                    boolean isNew = !aliveApps.containsKey(appKey);

                    aliveApps.put(appKey, currentTime);

                    if (isNew) {
                        hasChanges = true;
                        System.out.println("New app append: " + appKey);
                    }
                } else if (BYE_MESSAGE.equals(messageType)) {
                    if (aliveApps.remove(appKey) != null) {
                        hasChanges = true;
                        System.out.println("App delete: " + appKey);
                    }
                }

            } catch (SocketTimeoutException ignored) {
            }
            catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.err.println("Receiver error: " + e.getMessage());
                }
            }
        }
    }

    private void cleanerTask() {
        try {
            long currentTime = System.currentTimeMillis();

            boolean removedAny = aliveApps.entrySet().removeIf(entry -> {
                long timeSinceLastSeen = currentTime - entry.getValue();
                if (timeSinceLastSeen > MAX_TIME_MS) {
                    System.out.println("App delete: " + entry.getKey());
                    return true;
                }
                return false;
            });

            if (hasChanges || removedAny) {
                printAliveApps();
                hasChanges = false;
            }

        } catch (Exception e) {
            System.err.println("Cleaner error: " + e.getMessage());
        }
    }

    private void printAliveApps() {
        if (aliveApps.isEmpty()) {
            System.out.println("No alive another apps");
        } else {
            System.out.println("Alive apps (" + aliveApps.size() + "): " +
                    String.join(", ", aliveApps.keySet()));
        }
    }

    private void sendLeaveMessage() {
        if (multicastSocket == null || multicastSocket.isClosed()) {
            return;
        }
        try {
            String message = String.join("|", BYE_MESSAGE, localAddress, processID);
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, multicastGroup, PORT);
            multicastSocket.send(packet);
        } catch (Exception e) {
            System.err.println("Sender error: " + e.getMessage());
        }
    }

    private void shutdown() {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return;
        }

        running = false;
        sendLeaveMessage();

        executor.shutdownNow();

        if (multicastSocket != null && !multicastSocket.isClosed()) {
            multicastSocket.close();
        }
    }

    private LocalEndpoint resolveLocalEndpoint(boolean useIPv6) throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || !networkInterface.supportsMulticast() || networkInterface.isLoopback()) {
                continue;
            }

            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            InetAddress fallback = null;
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
                    continue;
                }

                if (useIPv6 && address instanceof Inet6Address) {
                    if (!address.isLinkLocalAddress()) {
                        return new LocalEndpoint(networkInterface, address);
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }

                if (!useIPv6 && address instanceof Inet4Address) {
                    return new LocalEndpoint(networkInterface, address);
                }
            }

            if (fallback != null) {
                return new LocalEndpoint(networkInterface, fallback);
            }
        }
        return null;
    }

    private static final class LocalEndpoint {
        private final NetworkInterface networkInterface;
        private final InetAddress address;

        private LocalEndpoint(NetworkInterface networkInterface, InetAddress address) {
            this.networkInterface = networkInterface;
            this.address = address;
        }
    }
}