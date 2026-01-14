package org.example;

import org.example.controller.GameController;
import org.example.ui.swing.SwingUI;

//главный класс приложения для запуска многопользовательской игры змейка
public class SnakeGame {
    //точка входа в программу, инициализирует контроллер и пользовательский интерфейс
    public static void main(String[] args) {
        //создаёт контроллер для управления игровой логикой и сетевым взаимодействием
        GameController controller = new GameController();
        //создаёт графический интерфейс на основе swing и связывает его с контроллером
        SwingUI ui = new SwingUI(controller);
        
        //устанавливает обратную связь между контроллером и интерфейсом
        controller.setUI(ui);
        //запускает отображение пользовательского интерфейса
        ui.start();
    }
}
