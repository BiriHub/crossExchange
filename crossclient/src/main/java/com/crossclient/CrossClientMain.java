package com.crossclient;

import java.io.*;
import java.net.*;

import com.google.gson.*;

import java.util.Calendar;
import java.util.Map;
import java.util.Properties;

public class CrossClientMain {

    private static final String CONFIG_FILE = "client.properties"; // Configuration file

    private String serverHost; // Server host
    private int serverPort; // Server port
    private Socket socket; // Socket

    private DatagramSocket datagramSocket; // Datagram socket for order notifications

    private long userSessionTimestamp; // User session timestamp: used to check the user session
    private long maxLoginTime; // Maximum login time: it is sent by the server to the client when the user logs
                               // in
    private String usernameLoggedIn; // Username of the user logged in

    private BufferedReader input; // Input stream of the socket
    private PrintWriter output; // Output stream of the socket
    private final Gson gson = new Gson();
    private final ExecutorService udpNotificationListener;

    public CrossClientMain() throws IOException {
        // Load the default configuration and connect to the server
        loadConfiguration();
        udpNotificationListener = Executors.newSingleThreadExecutor();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Shutdown the UDP notification listener
            udpNotificationListener.shutdownNow();
            try {
                if (amIlogged()) {
                    logout();
                }
            } catch (IOException e) {
                System.err.println("Error while closing the user session: " + e.getMessage());
            }
            disconnect();
        }));
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
            datagramSocket = new DatagramSocket();

            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            output = new PrintWriter(socket.getOutputStream(), true);
            System.out.println("[!] Connesso al server " + serverHost + ":" + serverPort);
        } catch (IOException e) {
            throw new IOException("Connection error: " + e.getMessage());
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

    /*
     * Listen for notifications from the server,
     */
    public void udpNotificationListener() {
        udpNotificationListener.execute(() -> {
            try {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    datagramSocket.receive(packet);
                    JsonObject udpNotification = JsonParser
                            .parseString(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    System.out.println("========================\n[!] New notification");
                    if (udpNotification.has("notification") && udpNotification.has("trades")) {
                        String notification = udpNotification.get("notification").getAsString();
                        System.out.println("[!] Notification: " + notification);
                        JsonArray trades = udpNotification.get("trades").getAsJsonArray();
                        for (JsonElement trade : trades) {
                            JsonObject tradeObj = trade.getAsJsonObject();
                            System.out.println("Order ID: " + tradeObj.get("orderId").getAsString());
                            System.out.println("Type: " + tradeObj.get("type").getAsString());
                            System.out.println("Size: " + tradeObj.get("size").getAsString());
                            System.out.println("Price: " + tradeObj.get("price").getAsString());
                            System.out.println("Timestamp: " + tradeObj.get("timestamp").getAsString());
                            System.out.println("-------------");
                        }
                        System.out.println("========================");

                    }
                }
            } catch (SocketException e) {
                System.err.println("Error while setting up UDP listener : " + e.getMessage());
            } catch (IOException e) {
                System.err.println("Error while receiving notifications: " + e.getMessage());
            }
        });
    }

    // User registration
    private void register(BufferedReader console) throws IOException {
        String username;
        String password;
        boolean flag = false;
        do {
            System.out.print("Username: ");
            username = console.readLine();
            System.out.print("Password: ");
            password = console.readLine();
            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("Username and password must not be empty.");
                flag = true;
            }

        } while (flag);

        String request = gson.toJson(Map.of("operation", "register", "values", Map.of("username", username, "password",
                password)));
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
            String request = gson.toJson(Map.of("operation", "updateCredentials", "values",
                    Map.of("username", username, "currentPassword", currentPassword, "newPassword", newPassword)));
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

            String request = gson.toJson(Map.of("operation", "login", "values",
                    Map.of("username", username, "password", password)));
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

    // Check if the user is logged in instead of sending the request to the server
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

        output.println(Map.of("operation", "logout", "values", Map.of("userId", usernameLoggedIn)));

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

        String request = gson.toJson(Map.of("operation", "insertLimitOrder", "values", Map.of("userId", usernameLoggedIn,
                "type", type, "size", size, "price", price)));
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

        String request = gson.toJson(Map.of("operation", "insertMarketOrder", "values", Map.of("userId", usernameLoggedIn,
                "type", type, "size", size)));
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

    private void insertStopOrder(BufferedReader console) throws IOException {
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
                    System.out.println("Command not recognized. Please select a valid type.");
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

        String request = gson.toJson(Map.of("operation", "insertStopOrder", "values", Map.of("userId", usernameLoggedIn,
                "type", type, "size", size, "price", price)));
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

    // Cancel order
    private void cancelOrder(BufferedReader console) throws IOException {
        if (!amIlogged()) {
            System.out.println("[!] orderId: -1"); // the user is not logged in
            return;
        }

        int orderId = 0;
        do {
            System.out.print("Order ID: ");
            try {
                orderId = Integer.parseInt(console.readLine());
                if (orderId <= 0)
                    System.out.println("Error: order ID must be a positive number.");

            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number.");
            }
        } while (orderId <= 0);

        String request = gson
                .toJson(Map.of("operation", "cancelOrder", "values", Map.of("userId", usernameLoggedIn, "orderId", orderId)));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage") && jsonResponse.has("newUserSession")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);

        }
    }

    /*
     * Get the list of fulfilled orders
     */
    private void getPriceHistory(BufferedReader console) throws IOException {
        if (!amIlogged()) {
            System.out.println("[!] Server response code: 101 - user not logged in"); // locally check if the user is
                                                                                      // logged in and print the message
                                                                                      // instead of sending the request
                                                                                      // to the server
            return;
        }

        String line;

        boolean validInput = false;
        Calendar currCalendar = Calendar.getInstance();
        int currentYear = currCalendar.get(Calendar.YEAR);
        int currentMonth = currCalendar.get(Calendar.MONTH) + 1;
        do {
            System.out.print("Select month and year in the format (MMYYYY): ");
            line = console.readLine();
            if (line.isEmpty() || line.length() != 6) {
                System.out.println("Invalid input. Please enter a valid month and year in the format MMYYYY.");
                continue;
            }
            try {
                int monthValue = Integer.parseInt(line.substring(0, 2));
                int yearValue = Integer.parseInt(line.substring(2));
                if (monthValue < 1 || monthValue > 12)
                    System.out.println("Invalid month. Please enter a month between 01 and 12.");
                else if (yearValue > currentYear)
                    System.out.println("Invalid year. Please enter a year less than or equal to the current year.");
                else if (yearValue == currentYear && monthValue > currentMonth)
                    System.out.println("Invalid month. Please enter a month less than or equal to the current month.");
                else
                    validInput = true;
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter numeric values for month and year.");
            }
        } while (!validInput);

        String request = gson.toJson(
                Map.of("operation", "getPriceHistory", "values", Map.of("month", line, "userId", usernameLoggedIn)));

        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage") && jsonResponse.has("newUserSession")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);

        } else if (jsonResponse.has("month") && jsonResponse.has("tradeHistory")
                && jsonResponse.has("newUserSession")) {

            userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            String month = jsonResponse.get("month").getAsString();
            JsonArray tradeHistory = jsonResponse.get("tradeHistory").getAsJsonArray();

            System.out.println("Price history for month " + month + ":");
            for (JsonElement trade : tradeHistory) {
                JsonObject tradeObj = trade.getAsJsonObject();
                System.out.println("Day: " + tradeObj.get("day").getAsString());
                System.out.println("Opening price: " + tradeObj.get("openingPrice").getAsString());
                System.out.println("Closing price: " + tradeObj.get("closingPrice").getAsString());
                System.out.println("Highest price: " + tradeObj.get("highestPrice").getAsString());
                System.out.println("Lowest price: " + tradeObj.get("lowestPrice").getAsString());
                System.out.println("Fulfilled orders: ");
                JsonArray fulfilledOrders = tradeObj.get("fulfilledOrders").getAsJsonArray();
                for (JsonElement order : fulfilledOrders) {
                    JsonObject orderObj = order.getAsJsonObject();
                    System.out.println("Order ID: " + orderObj.get("orderId").getAsString());
                    System.out.println("Type: " + orderObj.get("type").getAsString());
                    System.out.println("Size: " + orderObj.get("size").getAsString());
                    System.out.println("Price: " + orderObj.get("price").getAsString());
                    System.out.println("Timestamp: " + orderObj.get("timestamp").getAsString());
                    System.out.println("-------------");
                }
            }

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
                System.out.println("6. Change user/logout");
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
        switch (command) {
            case "1":
                insertLimitOrder(console);
                break;
            case "2":
                insertMarketOrder(console);
                break;
            case "3":
                insertStopOrder(console);
                break;
            case "4":
                cancelOrder(console);
                break;
            case "5":
                getPriceHistory(console);
                break;
            case "6":
                logout(console);
                break;
            default:
                System.out.println("Command not recognized.Please select a valid operation.");
                break;
        }
    }

    // Main
    public static void main(String[] args) {
        try {
            CrossClientMain client = new CrossClientMain();
            client.connectToServer();
            client.start();
        } catch (IOException e) {
            System.err.println("Client start on failure: " + e.getMessage());
        }
    }

}
