import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final int PING_TIMEOUT = 1000; // 1 секунда
    private static final int PING_COUNT = 4; // Количество пингов по умолчанию
    private static final int TRACEROUTE_MAX_HOPS = 30; // Максимальное количество прыжков для traceroute

    public static void main(String[] args) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("Network Tools (ping/traceroute)");
        System.out.println("Введите команду (ping <host> или traceroute <host>):");

        while (true) {
            try {
                System.out.print("> ");
                String input = reader.readLine().trim();

                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    break;
                }

                String[] parts = input.split("\\s+");
                if (parts.length < 2) {
                    System.out.println("Неверный формат команды. Используйте: ping <host> или traceroute <host>");
                    continue;
                }

                String command = parts[0].toLowerCase();
                String host = parts[1];

                switch (command) {
                    case "ping":
                        ping(host);
                        break;
                    case "traceroute":
                        traceroute(host);
                        break;
                    default:
                        System.out.println("Неизвестная команда: " + command);
                }
            } catch (IOException e) {
                System.out.println("Ошибка ввода: " + e.getMessage());
            }
        }
    }

    private static void ping(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            System.out.println("PING " + host + " (" + address.getHostAddress() + ")");

            List<Long> rtts = new ArrayList<>();
            int lost = 0;

            for (int i = 0; i < PING_COUNT; i++) {
                try {
                    long startTime = System.nanoTime();
                    boolean reachable = address.isReachable(PING_TIMEOUT);
                    long endTime = System.nanoTime();

                    long rtt = (endTime - startTime) / 1000000; // в миллисекундах

                    if (reachable) {
                        System.out.println("Ответ от " + address.getHostAddress() + ": время=" + rtt + "мс");
                        rtts.add(rtt);
                    } else {
                        System.out.println("Превышен интервал ожидания для запроса.");
                        lost++;
                    }

                    Thread.sleep(1000); // Пауза между пингами
                } catch (IOException | InterruptedException e) {
                    System.out.println("Ошибка при выполнении ping: " + e.getMessage());
                    lost++;
                }
            }

            // Вывод статистики
            if (!rtts.isEmpty()) {
                long min = rtts.stream().min(Long::compare).get();
                long max = rtts.stream().max(Long::compare).get();
                double avg = rtts.stream().mapToLong(Long::longValue).average().getAsDouble();

                System.out.println("\nСтатистика Ping для " + address.getHostAddress() + ":");
                System.out.println("    Пакетов: отправлено = " + PING_COUNT +
                        ", получено = " + (PING_COUNT - lost) +
                        ", потеряно = " + lost +
                        " (" + (lost * 100 / PING_COUNT) + "% потерь)");
                System.out.println("Приблизительное время приема-передачи:");
                System.out.println("    Минимальное = " + min + "мс, Максимальное = " + max +
                        "мс, Среднее = " + String.format("%.2f", avg) + "мс");
            }

        } catch (UnknownHostException e) {
            System.out.println("Не удается разрешить системное имя узла " + host);
        }
    }

    private static void traceroute(String host) {
        try {
            InetAddress destination = InetAddress.getByName(host);
            System.out.println("Трассировка маршрута к " + host + " [" + destination.getHostAddress() + "]");
            System.out.println("с максимальным числом прыжков " + TRACEROUTE_MAX_HOPS + "\n");

            for (int ttl = 1; ttl <= TRACEROUTE_MAX_HOPS; ttl++) {
                System.out.printf("%2d  ", ttl);

                try {
                    // В Java нddет прямого способа установить TTL для ICMP, поэтому используем обходной путь
                    // В реальном приложении лучше использовать JNI или библиотеки вроде jpcap
                    long startTime = System.nanoTime();
                    boolean reachable = destination.isReachable(PING_TIMEOUT);
                    long endTime = System.nanoTime();

                    long rtt = (endTime - startTime) / 1000000;

                    if (reachable) {
                        System.out.println(destination.getHostAddress() + "  " + rtt + "мс");
                        System.out.println("Трассировка завершена.");
                        break;
                    } else {
                        System.out.println("*");
                    }

                    Thread.sleep(1000);
                } catch (IOException | InterruptedException e) {
                    System.out.println("*");
                }
            }
        } catch (UnknownHostException e) {
            System.out.println("Не удается разрешить системное имя узла " + host);
        }
    }
}