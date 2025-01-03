package com.crossclient;

import java.io.*;
import java.net.*;

import com.google.gson.*;

import java.util.Map;
import java.util.Properties;

public class CrossClientMain {

    private static final String CONFIG_FILE = "client.properties"; // Configuration file

    private String serverHost; // Server host
    private int serverPort; // Server port
    private Socket socket; // Socket

    private long userSessionTimestamp; // User session timestamp: used to check the user session
    private long maxLoginTime; // Maximum login time: it is sent by the server to the client when the user logs
                               // in
    private String usernameLoggedIn; // Username of the user logged in

    private BufferedReader input; // Input stream of the socket
    private PrintWriter output; // Output stream of the socket
    private final Gson gson = new Gson();

    public CrossClientMain() throws IOException {
        // Load the default configuration and connect to the server
        loadConfiguration();
    }

    // Load the default configuration of client
    /*
     * Load the default configuration of client
     * 
     * @throws IOException if an I/O error occurs
     */
    private void loadConfiguration() {
        try (InputStream configFileStream = CrossClientMain.class.getResourceAsStream(CONFIG_FILE)) {
            Properties config = new Properties();
            config.load(configFileStream);
            serverHost = config.getProperty("server");
            serverPort = Integer.parseInt(config.getProperty("port"));

        } catch (NullPointerException e) {
            System.err.println("Configuration file has not been found :" + CONFIG_FILE);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error while reading configuration file: " + e.getMessage());
            System.exit(1);
        }
    }

    // Establish connection to the server
    private void connectToServer() throws IOException {
        try {
            socket = new Socket(serverHost, serverPort);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            output = new PrintWriter(socket.getOutputStream(), true);
            System.out.println("[!] Connesso al server " + serverHost + ":" + serverPort);
        } catch (IOException e) {
            throw new IOException("Errore di connessione al server.", e);
        }
    }

    // Disconnect from the server
    private void disconnect() {
        try {
            if (socket != null)
                socket.close();
        } catch (IOException e) {
            System.err.println("Disconnection error: " + e.getMessage());
        }
    }

    // User registration
    private void register(BufferedReader console) throws IOException {
        System.out.print("Username: ");
        String username = console.readLine();
        System.out.print("Password: ");
        String password = console.readLine();

        String request = gson.toJson(Map.of("operation", "register", "username", username, "password", password));
        System.out.println("Richista creata: " + request);
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);
        }

    }

    // Update user credentials
    private void updateCredentials(BufferedReader console) throws IOException {
        if (!amIlogged()) {

            System.out.print("Username: ");
            String username = console.readLine();
            System.out.print("Current password: ");
            String currentPassword = console.readLine();
            System.out.print("New password: ");
            String newPassword = console.readLine();
            String request = gson.toJson(Map.of("operation", "updateCredentials", "username", username, "old_password",
                    currentPassword, "new-password", newPassword));
            output.println(request);

            // Response parsing
            String response = input.readLine();
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            if (jsonResponse.has("response") && jsonResponse.has("errorMessage")) {
                int responseCode = jsonResponse.get("response").getAsInt();
                String errorMessage = jsonResponse.get("errorMessage").getAsString();
                System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);
            }
        } else {
            System.out.println("[!] Server response code: 104 - user currently logged in"); // locally check if the user
                                                                                            // is logged in and print
                                                                                            // the message instead of
                                                                                            // sending the request to
                                                                                            // the server
        }
    }

    // User login
    private void login(BufferedReader console) throws IOException {
        if (!amIlogged()) {
            System.out.print("Username: ");
            String username = console.readLine();
            System.out.print("Password: ");
            String password = console.readLine();

            String request = gson.toJson(Map.of("operation", "login", "username", username, "password", password));
            output.println(request);

            // Response parsing
            String response = input.readLine();
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            if (jsonResponse.has("session") && jsonResponse.has("response") && jsonResponse.has("errorMessage")) {

                int responseCode = jsonResponse.get("response").getAsInt();
                String errorMessage = jsonResponse.get("errorMessage").getAsString();

                // 100 - login successful
                if (responseCode == 100) {
                    maxLoginTime = jsonResponse.get("session").getAsLong(); // Save the maximum login time for users
                    userSessionTimestamp = System.currentTimeMillis(); // Save the timestamp of the beginning of user
                                                                       // session
                    usernameLoggedIn = username; // Save the username of the user logged in
                }

                System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);
            }
        } else {
            System.out.println("[!] Server response code: 102 - user currently logged in"); // locally check if the user
                                                                                            // is logged in and print
                                                                                            // the message instead of
                                                                                            // sending the request to
                                                                                            // the server
        }

    }

    // Check if the user is logged in
    // da verificare se è davvero necessaria localmente
    public boolean amIlogged() {
        return (System.currentTimeMillis() - userSessionTimestamp) < maxLoginTime && usernameLoggedIn != null;
    }

    // Logout
    private void logout(BufferedReader console) throws IOException {
        if (!amIlogged()) {
            System.out.println("[!] Server response code: 101 - user not logged in"); // locally check if the user is
                                                                                      // logged in and print the message
                                                                                      // instead of sending the request
                                                                                      // to the server
            return;
        }
        output.println(Map.of("operation", "logout", "username", usernameLoggedIn));

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            if (responseCode == 100) {
                userSessionTimestamp = 0; // Reset the user session timestamp
                usernameLoggedIn = null; // Reset the username of the user logged in
            }
            System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);

        }
    }

    // Insert limit order
    private void insertLimitOrder(BufferedReader console) throws IOException {
        if (!amIlogged()) {
            System.out.println("[!] orderId: -1"); // the user is not logged in
            return;
        }
        String type;
        long size = 0;
        long price = 0;

        do {
            System.out.print("Select the order type (ask/bid): ");
            System.out.println("1. Ask");
            System.out.println("2. Bid");
            type = console.readLine();
            switch (type) {
                case "1":
                    type = "ask";
                    break;
                case "2":
                    type = "bid";
                    break;
                default:
                    System.out.println("Command not recognized.Please select a valid type.");
            }

        } while ((!type.equals("ask") && !type.equals("bid")) || type.isEmpty());

        do {
            System.out.print("Size: ");
            try {
                size = Long.parseLong(console.readLine());
                if (size <= 0) {
                    System.out.println("Size must be a positive number.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number.");
            }
        } while (size <= 0);

        do {
            System.out.print("Price: ");
            try {
                price = Long.parseLong(console.readLine());
                if (price <= 0) {
                    System.out.println("Price must be a positive number.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number.");
            }
        } while (price <= 0);

        String request = gson.toJson(Map.of("operation", "insertLimitOrder", "username", usernameLoggedIn, "type",
                type, "size", size, "price", price));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("orderId") && jsonResponse.has("newUserSession")) {
            int orderId = jsonResponse.get("orderId").getAsInt();
            userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            System.out.println("[!] orderId: " + orderId);
        }
    }

    private void insertMarketOrder(BufferedReader console) throws IOException {

        if (!amIlogged()) {
            System.out.println("[!] orderId: -1"); // the user is not logged in
            return;
        }
        String type;
        long size = 0;
        long price = 0;

        do {
            System.out.print("Select the order type (ask/bid): ");
            System.out.println("1. Ask");
            System.out.println("2. Bid");
            type = console.readLine();
            switch (type) {
                case "1":
                    type = "ask";
                    break;
                case "2":
                    type = "bid";
                    break;
                default:
                    System.out.println("Command not recognized.Please select a valid type.");
            }

        } while ((!type.equals("ask") && !type.equals("bid")) || type.isEmpty());

        do {
            System.out.print("Size: ");
            try {
                size = Long.parseLong(console.readLine());
                if (size <= 0) {
                    System.out.println("Size must be a positive number.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number.");
            }
        } while (size <= 0);

        do {
            System.out.print("Price: ");
            try {
                price = Long.parseLong(console.readLine());
                if (price <= 0) {
                    System.out.println("Price must be a positive number.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number.");
            }
        } while (price <= 0);

        String request = gson.toJson(Map.of("operation", "insertMarketOrder", "username", usernameLoggedIn, "type",
                type, "size", size, "price", price));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("orderId")) {
            int orderId = jsonResponse.get("orderId").getAsInt();
            System.out.println("[!] orderId: " + orderId);
        }
    }

    // Menu principale
    public void start() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            String command;
            System.out.println("\n--- Menu CROSS ---");

            // First access: registration and login
            do {
                System.out.println(
                        "Before proceding with trading operations you firstly need to register an account and log in:");
                System.out.println("Select an operation:");
                System.out.println("1. Register");
                System.out.println("2. Login");
                System.out.println("3. Update credentials");
                System.out.println("4. Close the application");
                command = console.readLine();
                switch (command) {
                    case "1":
                        register(console);
                    case "2":
                        login(console);
                    case "3":
                        updateCredentials(console);
                    case "4":
                        break;
                    default:
                        System.out.println("Command not recognized.Please select a valid operation.");
                }

            } while (!amIlogged());

            // Main menu after login
            while (true) {

                System.out.println("\n-------------");
                System.out.println("Welcome " + usernameLoggedIn + "!");
                System.out.println("\n-------------");
                System.out.println("Select an operation:");
                System.out.println("1. Insert limit order");
                System.out.println("2. Insert market order");
                System.out.println("3. Insert stop order");
                System.out.println("4. Cancel order");
                System.out.println("5. Price history");
                System.out.println("6. Logout");
                System.out.println("7. Close the application");
                System.out.println("\n-------------");
                command = console.readLine();

                if (command.equals("7")) {
                    logout(console);
                    break;
                }
                handleCommand(command, console);
            }
        } catch (IOException e) {
            System.err.println("Errore di input/output: " + e.getMessage());
        } finally {
            disconnect();
        }
        System.out.print("[!] Client closing...");
    }

    // Gestione dei comandi
    private void handleCommand(String command, BufferedReader console) throws IOException {
        switch (command) { // TODO : add the operations
            case "1" -> insertLimitOrder(console);
            case "2" -> insertMarketOrder(console);
            case "3" -> insertStopOrder(console);
            case "4" -> cancelOrder(console);
            case "5" -> getPriceHistory(console);
            case "6" -> logout(console);
            default -> System.out.println("Command not recognized.Please select a valid operation.");
        }
    }

    // Main
    public static void main(String[] args) {
        try {
            CrossClientMain client = new CrossClientMain();
            client.connectToServer();
            client.start();
        } catch (IOException e) {
            System.err.println("Errore nell'avvio del client: " + e.getMessage());
        }
    }

}
