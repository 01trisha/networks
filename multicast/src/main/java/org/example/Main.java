package org.example;

public class Main {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("You forgot about <multicast-group-address>");
            System.exit(1);
        }

        try {
            Manager manager = new Manager(args[0]);
            manager.start();
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            System.exit(1);
        }
    }
}