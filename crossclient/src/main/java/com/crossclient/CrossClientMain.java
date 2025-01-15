package com.crossclient;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService udpNotificationExecutor;

    public CrossClientMain() throws IOException {
        // Load the default configuration and connect to the server
        loadConfiguration();
        udpNotificationExecutor = Executors.newSingleThreadExecutor();

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Shutdown the UDP notification listener
            udpNotificationExecutor.shutdownNow();
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

            // Start the UDP notification listener
            udpNotificationListener();
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

        udpNotificationExecutor.execute(() -> {
            try {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    datagramSocket.receive(packet);
                    JsonObject udpNotification = JsonParser.parseString(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    System.out.println("========================");
                    if (udpNotification.has("notification") && udpNotification.has("trades")) {
                        String notification = udpNotification.get("notification").getAsString();
                        System.out.println("[!] New Notification: " + notification);
                        JsonArray trades = udpNotification.get("trades").getAsJsonArray();
                        for (JsonElement trade : trades) {
                            JsonObject tradeObj = trade.getAsJsonObject();
                            System.out.println("Order ID: " + tradeObj.get("orderId").getAsString());
                            System.out.println("Type: " + tradeObj.get("type").getAsString());
                            System.out.println("Type of order: " + tradeObj.get("orderType").getAsString());
                            System.out.println("Size: " + tradeObj.get("size").getAsString());
                            System.out.println("Price: " + tradeObj.get("price").getAsString());
                            System.out.println("Timestamp: " + tradeObj.get("timestamp").getAsString());
                            System.out.println("-------------");
                        }
                        System.out.println("========================");

                    }
                }
            } catch (JsonSyntaxException e) {
                System.err.println("Error while parsing the notification: " + e.getMessage());
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
        boolean flag;
        System.out.println(
                "=== User registration ===\n The password must contains at least:\n - 8 and at maximum 20 characters\n - one uppercase letter\n - one lowercase letter\n - one number.\n =======================");
        do {
            flag = false;
            System.out.print("Username: ");
            username = console.readLine();
            System.out.print("Password: ");
            password = console.readLine();
            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("Username and password must not be empty.");
                flag = true;
            }
        } while (flag);

        String request = gson.toJson(Map.of("operation", "register", "values", Map.of("username", username, "password", password)));
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

    /*
     * Update user credentials JSON format of the request: { "operation": "updateCredentials",
     * "values": { "username": STRING, "currentPassword": STRING, "newPassword": STRING } }
     * JSON format of the response: { "response": INT, "errorMessage": STRING }
     */
    private void updateCredentials(BufferedReader console) throws IOException {
        if (!amIlogged()) {

            String username, currentPassword, newPassword;
            boolean flag = false;
            do {
                flag = false;
                System.out.print("Username: ");
                username = console.readLine();
                System.out.print("Current password: ");
                currentPassword = console.readLine();
                System.out.print("New password: ");
                newPassword = console.readLine();
                if (username.isEmpty() || currentPassword.isEmpty() || newPassword.isEmpty()) {
                    System.out.println("Username, current password and new password must not be empty.");
                    flag = true;
                }
            } while (flag);

            String request = gson.toJson(Map.of("operation", "updateCredentials", "values",
                    Map.of("username", username, "old_password", currentPassword, "new-password", newPassword)));
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

    /*
     * User login JSON format of the request: { "operation": "login", "values": { "username":
     * STRING, "password": STRING } JSON format of the response: { "response": INT,
     * "errorMessage": STRING, "session": LONG }
     */
    private void login(BufferedReader console) throws IOException {
        if (!amIlogged()) {

            String username, password;
            boolean flag;

            do {
                flag = false;
                System.out.print("Username: ");
                username = console.readLine();
                System.out.print("Password: ");
                password = console.readLine();
                if (username.isEmpty() || password.isEmpty()) {
                    System.out.println("Username and password must not be empty.");
                    flag = true;
                }

            } while (flag);

            String request = gson.toJson(Map.of("operation", "login", "values", Map.of("username", username, "password", password)));
            output.println(request);

            // Response parsing
            String response = input.readLine();
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            if (jsonResponse.has("response") && jsonResponse.has("errorMessage")) {

                int responseCode = jsonResponse.get("response").getAsInt();
                String errorMessage = jsonResponse.get("errorMessage").getAsString();

                // 100 - login successful
                if (responseCode == 100 && jsonResponse.has("session")) {
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

    /*
     * Logout the user JSON format of the request: { "operation": "logout", "values": {
     * "username": STRING } } JSON format of the response: { "response": INT, "errorMessage":
     * STRING }
     */
    private void logout() throws IOException {
        if (!amIlogged()) {
            System.out.println("[!] Server response code: 101 - user not logged in"); // locally check if the user is
                                                                                      // logged in and print the message
                                                                                      // instead of sending the request
                                                                                      // to the server
            return;
        }

        output.println(Map.of("operation", "logout", "values", Map.of("username", usernameLoggedIn)));

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);
            if (responseCode == 100) {
                System.out.println("[!] Logout for the user \"" + usernameLoggedIn + "\" successful.");
                userSessionTimestamp = 0; // Reset the user session timestamp
                usernameLoggedIn = null; // Reset the username of the user logged in
            }

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

        int udpPort = datagramSocket.getLocalPort();

        String request = gson.toJson(Map.of("operation", "insertLimitOrder", "values",
                Map.of("type", type, "size", size, "price", price, "userId", usernameLoggedIn, "udpPort", udpPort)));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("orderId")) {
            int orderId = jsonResponse.get("orderId").getAsInt();
            if (jsonResponse.has("newUserSession")) {
                userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            }
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

        int numPort = datagramSocket.getLocalPort();

        String request = gson.toJson(Map.of("operation", "insertMarketOrder", "values",
                Map.of("userId", usernameLoggedIn, "udpPort", numPort, "type", type, "size", size)));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("orderId")) {
            int orderId = jsonResponse.get("orderId").getAsInt();
            if (jsonResponse.has("newUserSession")) {
                userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            }
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

        int numPort = datagramSocket.getLocalPort();

        String request = gson.toJson(Map.of("operation", "insertStopOrder", "values",
                Map.of("userId", usernameLoggedIn, "udpPort", numPort, "type", type, "size", size, "price", price)));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("orderId")) {
            int orderId = jsonResponse.get("orderId").getAsInt();
            if (jsonResponse.has("newUserSession")) {
                userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            }
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

        String request = gson.toJson(Map.of("operation", "cancelOrder", "values", Map.of("userId", usernameLoggedIn, "orderId", orderId)));
        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            if (jsonResponse.has("newUserSession")) {
                userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp

            }
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

        String request = gson.toJson(Map.of("operation", "getPriceHistory", "values", Map.of("month", line, "userId", usernameLoggedIn)));

        output.println(request);

        // Response parsing
        String response = input.readLine();
        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
        if (jsonResponse.has("response") && jsonResponse.has("errorMessage") && jsonResponse.has("newUserSession")) {
            int responseCode = jsonResponse.get("response").getAsInt();
            String errorMessage = jsonResponse.get("errorMessage").getAsString();
            userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            System.out.println("[!] Server response code: " + responseCode + " - " + errorMessage);

        } else if (jsonResponse.has("month") && jsonResponse.has("tradeHistory") && jsonResponse.has("newUserSession")) {

            userSessionTimestamp = jsonResponse.get("newUserSession").getAsLong(); // Update the user session timestamp
            String month = jsonResponse.get("month").getAsString();

            SortedMap<String, JsonObject> tradeHistory = new TreeMap<>();
            Type type = new TypeToken<SortedMap<String, JsonObject>>() {}.getType();

            tradeHistory = gson.fromJson(jsonResponse.get("tradeHistory"), type);

            System.out.println("==========\n Price history for month " + month + ":");
            for (Map.Entry<String, JsonObject> trade : tradeHistory.entrySet()) {
                JsonObject tradeObj = trade.getValue();
                System.out.println("Day: " + tradeObj.get("numberOfDay").getAsString());
                System.out.println("Opening price: " + tradeObj.get("openingPrice").getAsString());
                System.out.println("Closing price: " + tradeObj.get("closingPrice").getAsString());
                System.out.println("Highest price: " + tradeObj.get("highestPrice").getAsString());
                System.out.println("Lowest price: " + tradeObj.get("lowestPrice").getAsString());
                System.out.println("Fulfilled orders: ");
                JsonArray fulfilledOrders = tradeObj.get("fulfilledOrders").getAsJsonArray();
                for (JsonElement order : fulfilledOrders) {
                    JsonObject orderObj = order.getAsJsonObject();
                    System.out.println("-------------");
                    System.out.println("\tOrder ID: " + orderObj.get("orderId").getAsString());
                    System.out.println("\tType: " + orderObj.get("type").getAsString());
                    System.out.println("\tSize: " + orderObj.get("size").getAsString());
                    System.out.println("\tPrice: " + orderObj.get("price").getAsString());
                    System.out.println("\tTimestamp: " + orderObj.get("timestamp").getAsString());
                }
            }

        }
    }

    // First access: registration and login
    private void notLoggedMenu(BufferedReader console) throws IOException {
        String command;
        System.out.println("\n--- Menu CROSS ---");
        System.out.println("Select an operation:");
        System.out.println("1. Register");
        System.out.println("2. Login");
        System.out.println("3. Change user profile password");
        System.out.println("4. Close the application");
        System.out.println("\n-------------");
        command = console.readLine();

        switch (command) {
        case "1":
            register(console);
            break;
        case "2":
            login(console);
            break;
        case "3":
            updateCredentials(console);
            break;
        case "4":
            System.out.println("[!] Client closing...");
            System.exit(0);
            break;
        default:
            System.out.println("Command not recognized.Please select a valid operation.");
            break;
        }
    }

    // Menu principale
    public void start() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            String command;
            while (true) {

                while (!amIlogged()) {
                    notLoggedMenu(console);
                }

                // Main menu after login
                System.out.println("\n-------------");
                System.out.println("Welcome " + usernameLoggedIn + "!");
                System.out.println("-------------");
                System.out.println("\n--- Menu CROSS ---");
                System.out.println("Select an operation:");
                System.out.println("1. Insert limit order");
                System.out.println("2. Insert market order");
                System.out.println("3. Insert stop order");
                System.out.println("4. Cancel order");
                System.out.println("5. Price history");
                System.out.println("6. Change user/logout");
                System.out.println("7. Close the application");
                System.out.println("--------------------");
                command = console.readLine();

                if (command.equals("7")) {
                    logout();
                    System.out.println("[!] Client closing...");
                    System.exit(0);
                }
                handleCommand(command, console);
            }
        } catch (SocketException e) {
            System.err.println("[!]The connection with server has been lost:" + e.getMessage());

        } catch (IOException e) {
            System.err.println("[!] Generic error: " + e.getStackTrace());
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
            logout();
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
