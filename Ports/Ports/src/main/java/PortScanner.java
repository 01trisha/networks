import scanner.MultiScanner;
import scanner.ServiceLoader;
import scanner.SingleScanner;

import java.util.*;

public class PortScanner{

    public static void main(String[] args){
        scanner.ServiceLoader serviceLoader = new ServiceLoader();

        Scanner scanner = new Scanner(System.in);
        while (true){
        System.out.print("""
                Выберите вариант работы:
                1 - проверить указанный порт
                2 - сканировать диапазон портов
                0 - выйти
                
                Ваш вариант:""");

        int mode = scanner.nextInt();

            switch (mode){
                case 1:
                    System.out.print("Введите номер порта: ");
                    int port = scanner.nextInt();
                    SingleScanner singleScanner = new SingleScanner(serviceLoader);
                    singleScanner.scan(port);
                    break;
                case 2:
                    System.out.print("введите начальный порт диапазона: ");
                    int startPort = scanner.nextInt();
                    System.out.print("введите конечный порт диапазона: ");
                    int endPort = scanner.nextInt();
                    MultiScanner multiScanner = new MultiScanner(serviceLoader);
                    multiScanner.scan(startPort, endPort);
                    break;
                case 0:
                    System.exit(0);
                default:
                    System.out.println("Неверный выбор");
            }
            System.out.println();
        }
    }
}