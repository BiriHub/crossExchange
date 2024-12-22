package com.crossserver;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.crossserver.models.*;
import com.crossserver.models.Session.SessionManager;
import com.google.gson.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
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
    private final SessionManager sessionManager;
    private final Gson gson;

    public CrossServerMain() {
        // Load the default configuration and connect to the server
        loadConfiguration();
        usersDB = new ConcurrentHashMap<>();
        sessionManager = new SessionManager(max_sessionTime);
        gson = new Gson();
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
            serverPort = Integer.parseInt(config.getProperty("port")); // extract the port from the configuration file
            max_sessionTime = Long.parseLong(config.getProperty("sessionTime")); // extract the maximum session time from the configuration file
            String serverAddress = config.getProperty("address"); // extract the server address from the configuration file
            serverSocket = new ServerSocket(serverPort,0,InetAddress.getByName(serverAddress) ); // 

            
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
        System.out.println("[!] Server started on port " + serverPort + ". Address: " + serverSocket.getInetAddress());
        while (true) {
            Socket clientSocket = serverSocket.accept();
            threadPool.execute(new UserHandler(clientSocket, this));
        }
    }

    // Registrazione utente
    public String register(JsonObject request) {
        String username = request.get("username").getAsString();
        String password = request.get("password").getAsString();

        if (username.isEmpty() || password.isEmpty()) {
            return gson.toJson(Map.of( "response", 103, "errorMessage", "User parameter not found"));
        }

        if (usersDB.containsKey(username)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Username not available"));
        }
        usersDB.put(username, password);

        return gson.toJson(Map.of("response",100,"errorMessage","OK"));
    }

    // User login
    // tested and working
    public String login(JsonObject request) {
        String username = request.get("username").getAsString();
        String old_password = request.get("password").getAsString();

        String password = usersDB.get(username);
        if (password == null || !old_password.equals(password)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Credenziali errate"));
        }

        // Start user session
        sessionManager.loginUser(username);

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK"));
    }

    // Update user credentials
    // tested and working
    public String updateCredentials(JsonObject request) {
        if (!request.has("username") || !request.has("old_password") || !request.has("new-password")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }
        String username = request.get("username").getAsString();
        String oldPassword = request.get("old_password").getAsString();
        String newPassword = request.get("new-password").getAsString();
        
        
        String user_password = usersDB.get(username);
        
        if (user_password == null || !user_password.equals(oldPassword)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Credentials are not correct"));
        }
        
        if (newPassword.equals(oldPassword)) {
            return gson.toJson(
                    Map.of("response", 103, "errorMessage", "New password must be different from the old one"));
        }

        usersDB.put(username, newPassword); // update the user's password

        return gson.toJson(Map.of("response", 100, "errorMessage", "Password updated successfully"));
    }

    // Logout
    // tested and working
    public String logout(JsonObject request){
        String username = request.get("username").getAsString();

        if (!sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "User is not logged in"));
        }
        return gson.toJson(Map.of("response", 100, "errorMessage", "You are now logged out"));
    }

    // Gestione Limit Order
    // public String handleLimitOrder(JsonObject request) {

    // }

    // // Gestione Market Order (placeholder)
    // public String handleMarketOrder(JsonObject request) {
    // }

    // // Gestione Stop Order (placeholder)
    // public String handleStopOrder(JsonObject request) {
    // }

    // // Cancellazione ordini (placeholder)
    // public String cancelOrder(JsonObject request) {

    // }

    // // Recupero storico prezzi (placeholder)
    // public String getPriceHistory(JsonObject request) {

    // }
    public static void main(String[] args) {
        try {
            CrossServerMain server = new CrossServerMain();
            server.start();
        } catch (IOException e) {
            System.err.println("Error while starting the server " + e.getMessage());
        }
    }

}
