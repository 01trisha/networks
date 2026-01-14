package org.example.ui.swing;

import me.ippolitov.fit.snakes.SnakesProto.Direction;
import me.ippolitov.fit.snakes.SnakesProto.GameConfig;
import org.example.controller.GameController;
import org.example.model.ConfigBuilder;
import org.example.model.GameEngine;
import org.example.ui.IGameUI;

import javax.swing.*;
import java.awt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//главное окно приложения реализующее интерфейс на swing
public class SwingUI extends JFrame implements IGameUI {
    private static final Logger logger = LoggerFactory.getLogger(SwingUI.class);
    private final GameController controller; //контроллер игры
    private MainMenuPanel menuPanel; //панель главного меню
    private GamePanel gamePanel; //панель игрового процесса
    private Timer renderTimer; //таймер для периодической перерисовки
    
    //создаёт главное окно и инициализирует его
    public SwingUI(GameController controller) {
        this.controller = controller;
        
        setTitle("Snake Game Online");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);
        
        showMenu();
    }
    
    @Override
    public void start() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
    
    //показывает главное меню
    private void showMenu() {
        SwingUtilities.invokeLater(() -> {
            getContentPane().removeAll();
            
            if (menuPanel == null) {
                menuPanel = new MainMenuPanel(controller, this);
            }
            
            add(menuPanel);
            revalidate();
            repaint();
        });
    }
    
    //вызывается когда игра начинается переключая на игровую панель
    @Override
    public void onGameStarted() {
        logger.info("onGameStarted called, scheduling UI update");
        SwingUtilities.invokeLater(() -> {
            logger.info("onGameStarted: executing UI update");
            getContentPane().removeAll();
            
            GameEngine engine = controller.getGameEngine();
            
            logger.info("Creating new GamePanel with engine={}", 
                engine != null ? engine.hashCode() : "null");
            
            //создаёт игровую панель с callback функциями
            gamePanel = new GamePanel(
                direction -> controller.sendSteer(direction),
                () -> controller.leaveGame(),
                () -> controller.becomeViewer(),
                () -> controller.rejoinGame(),
                () -> handleStartNewGame(),
                info -> handleJoinOtherGame(info),
                controller
            );
            
            add(gamePanel);
            revalidate();
            repaint();
            pack();
            setLocationRelativeTo(null);
            
            gamePanel.requestFocusInWindow();
            
            //останавливает предыдущий таймер если есть
            if (renderTimer != null) {
                renderTimer.stop();
            }
            
            //запускает таймер перерисовки каждые 100мс
            renderTimer = new Timer(100, e -> {
                if (gamePanel != null) {
                    gamePanel.repaint();
                }
            });
            renderTimer.start();
            
            logger.info("UI update completed");
        });
    }
    
    //обрабатывает создание новой игры через диалог
    private void handleStartNewGame() {
        SwingUtilities.invokeLater(() -> {
            JDialog dialog = new JDialog(this, "Создать новую игру", true);
            dialog.setLayout(new BorderLayout(10, 10));
            dialog.setSize(350, 200);
            dialog.setResizable(false);
            
            JPanel formPanel = new JPanel(new GridLayout(3, 2, 5, 5));
            formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            formPanel.add(new JLabel("Ваше имя:"));
            JTextField nameField = new JTextField(controller.getMyPlayerName() != null ? controller.getMyPlayerName() : "Player");
            formPanel.add(nameField);
            
            formPanel.add(new JLabel("Размер:"));
            String[] sizes = {"Малый (20x15)", "Средний (40x30)", "Большой (60x40)"};
            JComboBox<String> sizeCombo = new JComboBox<>(sizes);
            formPanel.add(sizeCombo);
            
            formPanel.add(new JLabel("Скорость:"));
            String[] speeds = {"Быстро (300мс)", "Нормально (500мс)", "Медленно (1000мс)"};
            JComboBox<String> speedCombo = new JComboBox<>(speeds);
            speedCombo.setSelectedIndex(1);
            formPanel.add(speedCombo);
            
            dialog.add(formPanel, BorderLayout.CENTER);
            
            JPanel buttonPanel = new JPanel();
            JButton createBtn = new JButton("Создать");
            JButton cancelBtn = new JButton("Отмена");
            
            //обработка создания игры
            createBtn.addActionListener(e -> {
                String name = nameField.getText().trim();
                if (name.isEmpty()) name = "Player";
                
                //определяет размер поля
                int width, height;
                switch (sizeCombo.getSelectedIndex()) {
                    case 0: width = 20; height = 15; break;
                    case 2: width = 60; height = 40; break;
                    default: width = 40; height = 30; break;
                }
                
                //определяет скорость игры
                int delay;
                switch (speedCombo.getSelectedIndex()) {
                    case 0: delay = 300; break;
                    case 2: delay = 1000; break;
                    default: delay = 500; break;
                }
                
                dialog.dispose();
                
                //выходит из текущей игры если нужно
                if (controller.getCurrentState() != GameController.GameState.IDLE) {
                    controller.leaveGameSilently();
                }
                
                GameConfig config = ConfigBuilder.createCustom(width, height, 1, delay);
                controller.startNewGame(name, config);
            });
            
            cancelBtn.addActionListener(e -> {
                dialog.dispose();
                if (gamePanel != null) {
                    gamePanel.requestFocusInWindow();
                }
            });
            
            buttonPanel.add(createBtn);
            buttonPanel.add(cancelBtn);
            dialog.add(buttonPanel, BorderLayout.SOUTH);
            
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }
    
    //обрабатывает присоединение к другой игре
    private void handleJoinOtherGame(GameController.GameInfo info) {
        String currentName = controller.getMyPlayerName();
        String playerName = JOptionPane.showInputDialog(this, "Введите ваше имя:", 
            currentName != null ? currentName : "Player");
        
        if (playerName != null && !playerName.trim().isEmpty()) {
            if (controller.getCurrentState() != GameController.GameState.IDLE) {
                controller.leaveGameSilently();
            }
            controller.joinGame(playerName.trim(), info.gameName, 
                me.ippolitov.fit.snakes.SnakesProto.NodeRole.NORMAL);
        }
    }
    
    //вызывается когда игра завершается возвращая в меню
    @Override
    public void onGameEnded() {
        SwingUtilities.invokeLater(() -> {
            if (renderTimer != null) {
                renderTimer.stop();
                renderTimer = null;
            }
            
            JOptionPane.showMessageDialog(this, 
                "Игра завершена!", 
                "Конец игры", 
                JOptionPane.INFORMATION_MESSAGE);
            
            showMenu();
        });
    }
    
    //вызывается при обновлении состояния игры для перерисовки
    @Override
    public void onGameStateUpdated() {
        if (gamePanel != null && controller.getMyPlayerId() >= 0) {
            SwingUtilities.invokeLater(() -> gamePanel.repaint());
        }
    }
    
    //отображает ошибку пользователю
    @Override
    public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, 
                message, 
                "Ошибка", 
                JOptionPane.ERROR_MESSAGE);
        });
    }
}
