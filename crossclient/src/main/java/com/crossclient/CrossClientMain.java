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
            System.out.println("[!] Response code: " + responseCode + " - " + errorMessage);
        }

    }

    // Update user credentials
    private void updateCredentials(BufferedReader console) throws IOException {
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
            System.out.println("[!] Response code: " + responseCode + " - " + errorMessage);
        }
    }

    // User login
    private void login(BufferedReader console) throws IOException {
        System.out.print("Username: ");
        String username = console.readLine();
        System.out.print("Password: ");
        String password = console.readLine();

        String request = gson.toJson(Map.of("operation", "login", "username", username, "password", password));
        output.println(request);
        handleSessionResponse();
    }

    // Logout
    private void logout(BufferedReader console) throws IOException {
        System.out.print("Username: "); // Ask the user to enter the username of the profile to logout
        String username = console.readLine();
        output.println(Map.of("operation", "logout", "username", username));
        handleSessionResponse();
    }

    // // Handle server response about user activity
    // private void handleSessionResponse() throws IOException {
    // UserSessionResponse response =
    // gson.fromJson(input.readLine(),UserSessionResponse.class);

    // System.out.println(response.toString()); // print the server response
    // }
    // Handle server response about user activity
    // // private void handleSessionResponse() throws IOException {
    // // // TODO : da completare
    // // String jsonResponse = input.readLine();
    // // JsonObject response =
    // JsonParser.parseString(jsonResponse).getAsJsonObject();
    // // if (response.has("response")) {
    // // int responseCode = response.get("response").getAsInt();
    // // String errorMessage = response.get("errorMessage").getAsString();
    // // UserSessionResponse userSessionResponse = new
    // UserSessionResponse(responseCode, errorMessage);
    // // System.out.println(userSessionResponse.toString()); // print the server
    // response
    // // } else
    // // System.out.println(response.toString()); // print the server response
    // // }

    // Menu principale
    public void start() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            String command;
            while (true) {

                if (userSessionTimestamp == 0 || System.currentTimeMillis() - userSessionTimestamp > 60000) {
                    System.out.println("Sessione scaduta. Eseguire nuovamente il login.");
                    userSessionTimestamp = 0;
                }
                System.out.println("\n--- Menu CROSS ---");
                System.out.println("1. Register");
                System.out.println("2. Update credentials");
                System.out.println("3. Login");
                System.out.println("4. Logout");
                System.out.println("0. Exit");
                System.out.print("Choose the operation: ");
                command = console.readLine();

                if (command.equals("0"))
                    break;
                handleCommand(command, console);
            }
        } catch (IOException e) {
            System.err.println("Errore di input/output: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    // Gestione dei comandi
    private void handleCommand(String command, BufferedReader console) throws IOException {
        switch (command) {
            case "1" -> register(console);
            case "2" -> updateCredentials(console);
            case "3" -> login(console);
            case "4" -> logout(console);
            default -> System.out.println("Comando non riconosciuto.");
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
