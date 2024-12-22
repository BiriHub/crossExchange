package com.crossserver;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Type;

import com.crossserver.models.*;
import com.crossserver.models.Session.SessionManager;
import com.google.gson.*;
import com.google.gson.internal.bind.MapTypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class CrossServerMain {

    private static final String CONFIG_FILE = "server.properties"; // Configuration file
    private static final String USERS_DB = "users.json"; // User database file

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
        loadDatabases(USERS_DB);
        // Save the state of the server when it is shut down
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveDatabases(USERS_DB);
            System.out.println("Server state saved successfully");
        }));
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
            max_sessionTime = Long.parseLong(config.getProperty("sessionTime")); // extract the maximum session time
                                                                                 // from the configuration file
            String serverAddress = config.getProperty("address"); // extract the server address from the configuration
                                                                  // file
            serverSocket = new ServerSocket(serverPort, 0, InetAddress.getByName(serverAddress)); //

        } catch (NullPointerException e) {
            System.err.println("Configuration file has not been found :" + CONFIG_FILE);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error while reading configuration file: " + e.getMessage());
            System.exit(1);
        }
    }

    private void saveDatabases(String filename) {
        // Load the user database
        try (Writer writer = new FileWriter(filename)) {
            gson.toJson(usersDB, writer);
            // System.out.println("State saved successfully to " + filename);
        } catch (IOException e) {
            System.err.println("Error saving database state of registered users to file: " + e.getMessage());
        }

    }

    private void loadDatabases(String filename) {
        // Save the user database

        try (Reader reader = new FileReader(filename)) {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> map = gson.fromJson(reader, type);
            if (map != null) {
                usersDB.putAll(map); // copy data to the usersDB
                // System.out.println("State loaded successfully from " + filename);
            }
        } catch (FileNotFoundException e) {
            System.out.println("No previous state file found, starting fresh.");
        } catch (IOException e) {
            System.err.println("Error loading state from file: " + e.getMessage());
        }
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b); // ensures that the byte is treated as an unsigned value.
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error while hashing password", e);
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
            return gson.toJson(Map.of("response", 103, "errorMessage", "User parameter not found"));
        }

        if (usersDB.containsKey(username)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Username not available"));
        }
        usersDB.put(username, hashPassword(password));

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK"));
    }

    // Login utente
    public String login(JsonObject request) {
        String username = request.get("username").getAsString();
        String password = request.get("password").getAsString();

        String storedPassword = usersDB.get(username);
        String checkPassword= hashPassword(password);
        
        if (password == null || !storedPassword.equals(checkPassword)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Credenziali errate"));
        }

        return gson.toJson(Map.of("response", 100, "errorMessage", ""));
    }

    // Aggiornamento credenziali
    public String updateCredentials(JsonObject request) {
        String username = request.get("username").getAsString();
        String oldPassword = request.get("password").getAsString();
        String newPassword = request.get("new_password").getAsString();

        if (newPassword.equals(oldPassword)) {
            return gson.toJson(
                    Map.of("response", 103, "errorMessage", "La nuova password non può essere uguale alla vecchia"));
        }

        String user_password = usersDB.get(username);

        if (user_password == null || !user_password.equals(oldPassword)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Credenziali errate"));
        }

        usersDB.put(username, newPassword); // update the user's password

        return gson.toJson(Map.of("response", 100, "errorMessage", "Password updated successfully"));
    }

    public String logout(JsonObject request) { // TODO: da finire di completare , vedi se è il caso o meno di aggiungere
                                               // parametri alla richiesta json tipo il nome dell'utente
        String username = request.get("username").getAsString();

        if (!sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "User is not logged in"));
        }
        return gson.toJson(Map.of("response", 100, "errorMessage", "You are now logged out"));
    }
    // TODO : previous methods need to be checked

    // // Gestione Limit Order
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
