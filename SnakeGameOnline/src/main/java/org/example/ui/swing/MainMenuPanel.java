package org.example.ui.swing;

import me.ippolitov.fit.snakes.SnakesProto.*;
import org.example.controller.GameController;
import org.example.model.ConfigBuilder;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

//панель главного меню для выбора действий
public class MainMenuPanel extends JPanel {
    private final GameController controller;
    private final SwingUI parent;
    private JLabel gamesCountLabel; //метка с количеством доступных игр
    private Timer refreshTimer; //таймер обновления счётчика игр
    
    public MainMenuPanel(GameController controller, SwingUI parent) {
        this.controller = controller;
        this.parent = parent;
        
        setLayout(new BorderLayout());
        initComponents();
        startRefreshTimer();
    }
    
    //запускает таймер обновления счётчика доступных игр
    private void startRefreshTimer() {
        refreshTimer = new Timer(1000, e -> {
            int count = controller.getAvailableGames().size();
            gamesCountLabel.setText("Available games: " + count);
        });
        refreshTimer.start();
    }
    
    //останавливает таймер обновления
    public void stopRefreshTimer() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }
    
    //инициализирует компоненты меню
    private void initComponents() {
        JLabel titleLabel = new JLabel("SNAKE GAME ONLINE", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 32));
        titleLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));
        add(titleLabel, BorderLayout.NORTH);
        
        JPanel menuPanel = new JPanel();
        menuPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        //кнопка создания новой игры
        JButton createGameBtn = new JButton("Create New Game");
        createGameBtn.setFont(new Font("Arial", Font.PLAIN, 18));
        createGameBtn.setPreferredSize(new Dimension(250, 50));
        createGameBtn.addActionListener(e -> showCreateGameDialog());
        menuPanel.add(createGameBtn, gbc);
        
        //кнопка присоединения к игре
        gbc.gridy++;
        JButton joinGameBtn = new JButton("Join Game");
        joinGameBtn.setFont(new Font("Arial", Font.PLAIN, 18));
        joinGameBtn.setPreferredSize(new Dimension(250, 50));
        joinGameBtn.addActionListener(e -> showJoinGameDialog());
        menuPanel.add(joinGameBtn, gbc);
        
        //счётчик доступных игр
        gbc.gridy++;
        gamesCountLabel = new JLabel("Available games: 0", SwingConstants.CENTER);
        gamesCountLabel.setFont(new Font("Arial", Font.ITALIC, 14));
        menuPanel.add(gamesCountLabel, gbc);
        
        //кнопка выхода
        gbc.gridy++;
        JButton exitBtn = new JButton("Exit");
        exitBtn.setFont(new Font("Arial", Font.PLAIN, 18));
        exitBtn.setPreferredSize(new Dimension(250, 50));
        exitBtn.addActionListener(e -> {
            stopRefreshTimer();
            controller.shutdown();
            System.exit(0);
        });
        menuPanel.add(exitBtn, gbc);
        
        add(menuPanel, BorderLayout.CENTER);
    }
    
    //показывает диалог создания новой игры
    private void showCreateGameDialog() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Create New Game", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(400, 250);
        
        JPanel formPanel = new JPanel(new GridLayout(3, 2, 5, 5));
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        formPanel.add(new JLabel("Your name:"));
        JTextField nameField = new JTextField("Player");
        formPanel.add(nameField);
        
        formPanel.add(new JLabel("Game size:"));
        String[] sizes = {"Small (20x15)", "Medium (40x30)", "Large (60x40)"};
        JComboBox<String> sizeCombo = new JComboBox<>(sizes);
        sizeCombo.setSelectedIndex(0);
        formPanel.add(sizeCombo);
        
        formPanel.add(new JLabel("Speed:"));
        String[] speeds = {"Fast (300ms)", "Normal (500ms)", "Slow (1000ms)"};
        JComboBox<String> speedCombo = new JComboBox<>(speeds);
        speedCombo.setSelectedIndex(1);
        formPanel.add(speedCombo);
        
        dialog.add(formPanel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel();
        JButton createBtn = new JButton("Create");
        JButton cancelBtn = new JButton("Cancel");
        
        createBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Name cannot be empty!");
                return;
            }
            
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
            
            GameConfig config = ConfigBuilder.createCustom(width, height, 1, delay);
            stopRefreshTimer();
            controller.startNewGame(name, config);
            dialog.dispose();
        });
        
        cancelBtn.addActionListener(e -> dialog.dispose());
        
        buttonPanel.add(createBtn);
        buttonPanel.add(cancelBtn);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    //показывает диалог присоединения к существующей игре
    private void showJoinGameDialog() {
        Map<String, GameController.GameInfo> games = controller.getAvailableGames();
        
        if (games.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "No games available.\n\nMake sure another instance is running\nand has created a game.\n\nWaiting for announcements...");
            return;
        }
        
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Join Game", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(500, 400);
        
        //создаёт список доступных игр
        DefaultListModel<String> listModel = new DefaultListModel<>();
        String[] gameKeys = games.keySet().toArray(new String[0]);
        
        for (String key : gameKeys) {
            GameController.GameInfo info = games.get(key);
            String item = String.format("%s - %dx%d, %d players, port:%d",
                    info.gameName,
                    info.config.getWidth(),
                    info.config.getHeight(),
                    info.players.size(),
                    info.masterPort);
            listModel.addElement(item);
        }
        
        JList<String> gameList = new JList<>(listModel);
        gameList.setSelectedIndex(0);
        JScrollPane scrollPane = new JScrollPane(gameList);
        dialog.add(scrollPane, BorderLayout.CENTER);
        
        //форма ввода имени и роли
        JPanel formPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        formPanel.add(new JLabel("Your name:"));
        JTextField nameField = new JTextField("Player2");
        formPanel.add(nameField);
        
        formPanel.add(new JLabel("Join as:"));
        String[] roles = {"Player", "Viewer"};
        JComboBox<String> roleCombo = new JComboBox<>(roles);
        formPanel.add(roleCombo);
        
        dialog.add(formPanel, BorderLayout.NORTH);
        
        JPanel buttonPanel = new JPanel();
        JButton joinBtn = new JButton("Join");
        JButton cancelBtn = new JButton("Cancel");
        
        //обработка присоединения к игре
        joinBtn.addActionListener(e -> {
            int selectedIndex = gameList.getSelectedIndex();
            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(dialog, "Please select a game!");
                return;
            }
            
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Name cannot be empty!");
                return;
            }
            
            String gameName = gameKeys[selectedIndex];
            NodeRole role = roleCombo.getSelectedIndex() == 0 ? NodeRole.NORMAL : NodeRole.VIEWER;
            
            stopRefreshTimer();
            controller.joinGame(name, gameName, role);
            dialog.dispose();
        });
        
        cancelBtn.addActionListener(e -> dialog.dispose());
        
        buttonPanel.add(joinBtn);
        buttonPanel.add(cancelBtn);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
}
