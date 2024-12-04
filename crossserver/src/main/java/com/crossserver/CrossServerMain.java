package com.crossserver;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.crossserver.models.*;
import com.google.gson.*;

import java.net.ServerSocket;
import java.net.Socket;
import java.security.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class CrossServerMain {

    private static final String CONFIG_FILE = "server.properties"; // Configuration file

    private final ConcurrentMap<String, String> usersDB;

    private ServerSocket serverSocket; // Server socket
    private int serverPort; // Server port
    private long max_sessionTime; // Maximum user session time

    private final ExecutorService threadPool;
    private final Gson gson = new Gson();

    public CrossServerMain() {
        // Load the default configuration and connect to the server
        loadConfiguration();
        usersDB = new ConcurrentHashMap<>();
        threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    /*
     * Load the default configuration of server
     * 
     */
    private void loadConfiguration() {
        try (InputStream configFileStream = CrossServerMain.class.getResourceAsStream(CONFIG_FILE)) {
            Properties config = new Properties();
            config.load(configFileStream);
            serverPort = Integer.parseInt(config.getProperty("port"));
            max_sessionTime = Long.parseLong(config.getProperty("sessionTime"));
            serverSocket = new ServerSocket(serverPort);

        } catch (NullPointerException e) {
            System.err.println("Configuration file has not been found :" + CONFIG_FILE);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error while reading configuration file: " + e.getMessage());
            System.exit(1);
        }
    }

    // start the server
    public void start() throws IOException {
        System.out.println("[!] Server started on port " + serverPort + "address: " + serverSocket.getInetAddress());
        while (true) {
            Socket clientSocket = serverSocket.accept();
            threadPool.execute(new UserHandler(clientSocket, this));
        }
    }

    // Registrazione utente
    private String register(JsonObject request) {
        String username = request.get("username").getAsString();
        String password = request.get("password").getAsString();

        if (username.isEmpty() || password.isEmpty()) {
            return gson.toJson("{\"response\":103, \"errorMessage\": \"User parameter not found \"}");
        }

        if (usersDB.containsKey(username)) {
            return gson.toJson("{\"response\":102, \"errorMessage\": \"Username not available \"}");
        }
        usersDB.put(username, password);

        return gson.toJson("{\"response\":100, \"errorMessage\": \"OK \"}");
    }

    // Login utente
    private String login(JsonObject request) {
        String username = request.get("username").getAsString();
        String old_password = request.get("password").getAsString();

        String password = usersDB.get(username);
        if (password == null || !old_password.equals(password)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Credenziali errate"));
        }

        return gson.toJson(Map.of("response", 100, "errorMessage", ""));
    }

    // Aggiornamento credenziali
    private String updateCredentials(JsonObject request) {
        String username = request.get("username").getAsString();
        String oldPassword = request.get("password").getAsString();
        String newPassword = request.get("new_password").getAsString();

        User user = usersDB.get(username);
        if (user == null || !user.getPassword().equals(oldPassword)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Credenziali errate"));
        }

        if (newPassword.equals(oldPassword)) {
            return gson.toJson(
                    Map.of("response", 103, "errorMessage", "La nuova password non può essere uguale alla vecchia"));
        }

        user.setPassword(newPassword);
        return gson.toJson(Map.of("response", 100, "errorMessage", ""));
    }

    // Gestione Limit Order
    private String handleLimitOrder(JsonObject request) {

    }

    // Gestione Market Order (placeholder)
    private String handleMarketOrder(JsonObject request) {
    }

    // Gestione Stop Order (placeholder)
    private String handleStopOrder(JsonObject request) {
    }

    // Cancellazione ordini (placeholder)
    private String cancelOrder(JsonObject request) {

    }

    // Recupero storico prezzi (placeholder)
    private String getPriceHistory(JsonObject request) {

    }

    }

    public static void main(String[] args) {
        try {
            CrossServerMain server = new CrossServerMain();
            server.start();
        } catch (IOException e) {
            System.err.println("Error while starting the server " + e.getMessage());
        }
    }

}
