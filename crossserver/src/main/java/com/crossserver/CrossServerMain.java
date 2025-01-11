package com.crossserver;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Type;

import com.crossserver.models.*;
import com.crossserver.models.Session.SessionManager;
import com.crossserver.models.orders.LimitOrder;
import com.crossserver.models.orders.Order;
import com.crossserver.models.orders.OrderBook;
import com.crossserver.models.orders.StopOrder;
import com.crossserver.models.orders.TradeHistory;
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
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class CrossServerMain {

    private static final String CONFIG_FILE = "server.properties"; // Configuration file
    private static final String USERS_DB = "users.json"; // User database file

    private final ConcurrentMap<String, String> usersDB; // User database : username, encrypPassword
    private final ConcurrentMap<String, UserHandler> activeUserConnections; // Active user connections

    private final OrderBook orderBook;
    private static AtomicLong orderIdCounter; // Order ID counter
    // TODO: when the server is started, the order ID counter must be initialized
    // with the last order ID from the order history, require to modify the
    // loadDatabases method

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
        orderBook = new OrderBook();
        activeUserConnections = new ConcurrentHashMap<>();
        orderIdCounter = new AtomicLong(0);
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
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            gson.toJson(usersDB, writer);

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

        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("username") || !values.has("password")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }

        String username = values.get("username").getAsString();
        String password = values.get("password").getAsString();

        if (username.isEmpty() || password.isEmpty()) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "User parameter not found"));
        }

        // check if the password matches the pattern
        // TODO to test
        if (!checkPassword(password)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid password"));
        }

        // check if the username is already taken
        if (usersDB.containsKey(username)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Username not available"));
        }
        usersDB.put(username, hashPassword(password));

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK"));
    }

    private boolean checkPassword(String password) {
        /*
         * ^ represents starting character of the string.
         * (?=.*[0-9]) represents a digit must occur at least once.
         * (?=.*[a-z]) represents a lower case alphabet must occur at least once.
         * (?=.*[A-Z]) represents an upper case alphabet that must occur at least once.
         * (?=\\S+$) white spaces don’t allowed in the entire string.
         * .{8, 20} represents at least 8 characters and at most 20 characters.
         * $ represents the end of the string.
         */
        String pattern = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\\S+$).{8,20}$";
        return password.matches(pattern);
    }

    // Update user credentials
    // tested and working
    public String updateCredentials(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 105, "errorMessage", "Missing parameters"));
        }

        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("username") || !values.has("old_password") || !values.has("new-password")) {
            return gson.toJson(Map.of("response", 105, "errorMessage", "Missing parameters"));
        }
        String username = values.get("username").getAsString();
        String oldPassword = values.get("old_password").getAsString();
        String newPassword = values.get("new-password").getAsString();

        String user_password = usersDB.get(username);

        if (!checkPassword(newPassword)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid new password"));
        }
        if (user_password == null) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Non-existent username"));
        }

        if (!user_password.equals(hashPassword(oldPassword))) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Username/old password mismatch"));
        }

        if (newPassword.equals(oldPassword)) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "New password equal to the old one"));
        }

        if (sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 104, "errorMessage", "User currently logged in"));
        }

        usersDB.put(username, hashPassword(newPassword)); // update the user's password

        return gson.toJson(Map.of("response", 100, "errorMessage", "Password updated successfully"));
    }

    // User login
    // tested and working
    public String login(JsonObject request, UserHandler activeConnection) {

        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("username") || !values.has("password")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }

        String username = values.get("username").getAsString();
        String password = values.get("password").getAsString();

        String storedPassword = usersDB.get(username);
        String checkPassword = hashPassword(password);

        // check if the user exists
        if (password == null) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Non existent username"));
        }

        // check if the user is already logged in
        if (sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "User already logged in"));
        }

        // check if the password is correct
        if (!storedPassword.equals(checkPassword)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Username/password mismatch"));
        }

        // Start user session
        sessionManager.loginUser(username);

        // Save the user connection
        activeUserConnections.put(username, activeConnection);

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK"));
    }

    // Logout
    // tested and working
    public String logout(JsonObject request, UserHandler activeConnection) {

        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!request.has("username")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        String username = values.get("username").getAsString();

        // Check if the username corresponds to the current active connection
        if (!activeConnection.equals(activeUserConnections.get(username))) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Username/connection mismatch"));
        }

        // check if the user is already logged in
        if (!sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "User not logged in"));
        }

        // check if the user exists
        if (usersDB.get(username) == null) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Non existent username"));
        }

        if (!sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "User is not logged in"));
        }
        activeUserConnections.remove(username);

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK"));
    }

    // Manage limit order request
    public String handleLimitOrderRequest(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("type") || !values.has("size") || !values.has("price")
                || !values.has("userId")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }

        String type = values.get("type").getAsString();
        long size = values.get("size").getAsLong();
        long price = values.get("price").getAsLong();

        if ((!type.equals("bid") && !type.equals("ask"))
                || size <= 0 || price <= 0) {
            return gson.toJson(Map.of("orderID", -1)); // error
        }

        // limit order creation
        String userId = values.get("userId").getAsString();

        LimitOrder order = new LimitOrder(orderIdCounter.getAndIncrement(), type, size, price);
        order.setUserId(userId);

        orderBook.insertLimitOrder(order); // insert the order in the order book
        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity
        return gson.toJson(Map.of("orderID", order.getOrderId(), "newUserSession", updatedUserSessionTime));
    }

    // Gestione Market Order (placeholder)
    public String handleMarketOrderRequest(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("type") || !values.has("size") || !values.has("userId")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }

        String type = values.get("type").getAsString();

        // market order creation
        long size = values.get("size").getAsLong();
        String userId = values.get("userId").getAsString();

        if ((!type.equals("bid") && !type.equals("ask"))
                || size <= 0) {
            return gson.toJson(Map.of("orderID", -1)); // error
        }

        // insert the order in the order book and return its identifier
        long executedOrderid = orderBook.insertMarketOrder(orderIdCounter.getAndIncrement(), type, size, userId);

        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity
        return gson.toJson(Map.of("orderID", executedOrderid, "newUserSession", updatedUserSessionTime));

    }

    public String handleStopOrderRequest(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("type") || !values.has("size") || !values.has("price") || !values.has("userId")) {
            return gson.toJson(Map.of("orderID", -1)); // Errore
        }

        String type = values.get("type").getAsString();
        long size = values.get("size").getAsLong();
        long price = values.get("price").getAsLong();
        String userId = values.get("userId").getAsString();

        if (!type.equals("bid") && !type.equals("ask")) {
            return gson.toJson(Map.of("orderID", -1)); // Errore
        }

        // stop order creation
        StopOrder stopOrder = new StopOrder(orderIdCounter.getAndIncrement(), type, size, price);
        stopOrder.setUserId(userId);

        orderBook.insertStopOrder(stopOrder); // insert the order in the order book
        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity
        return gson.toJson(Map.of("orderID", stopOrder.getOrderId(), "newUserSession", updatedUserSessionTime));
    }

    // Cancellazione ordini (placeholder)
    // TODO to test
    public String cancelOrder(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("orderId") || !values.has("userId")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        String userId = values.get("userId").getAsString();
        long orderId = values.get("orderId").getAsLong();

        Order order = orderBook.getOrder(orderId);

        if (order == null)
            return gson.toJson(Map.of("response", 101, "errorMessage", "Order does not exist"));
        if (!order.getUserId().equals(userId))
            return gson.toJson(Map.of("response", 101, "errorMessage", "Order belongs to different user"));
        if (order.isExecuted())
            return gson.toJson(Map.of("response", 101, "errorMessage", "Order has been executed"));

        orderBook.cancelOrder(orderId);
        return gson.toJson(Map.of("response", 100, "errorMessage", "OK")); // order has been deleted
    }

    public String getPriceHistory(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderID", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("month") || !values.has("userId")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        if (values.get("month").getAsString().length() != 6) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid month format"));
        }
        String userId = values.get("userId").getAsString();

        String month = values.get("month").getAsString().substring(0, 2);
        int year = Integer.parseInt(values.get("month").getAsString().substring(2));
        Calendar currCalendar = Calendar.getInstance();
        int currentYear = currCalendar.get(Calendar.YEAR);
        int currentMonth = currCalendar.get(Calendar.MONTH) + 1;
        int monthToInt = Integer.parseInt(month);

        if (monthToInt < 1 || monthToInt > 12)
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid month format"));
        else if (year > currentYear)
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid year format"));
        else if (year == currentYear && monthToInt > currentMonth)
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid month value"));

        LocalDate startOfMonth = LocalDate.of(year, monthToInt, 1);
        LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);
        long startOfMonthSeconds = startOfMonth.atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC);
        long endOfMonthSeconds = endOfMonth.atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC);
        ConcurrentSkipListMap<String, TradeHistory> orderHistory = orderBook.getOrderHistory(startOfMonthSeconds,
                endOfMonthSeconds);
        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity

        return gson
                .toJson(Map.of("newUserSession", updatedUserSessionTime, "month", month, "tradeHistory", orderHistory));
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
