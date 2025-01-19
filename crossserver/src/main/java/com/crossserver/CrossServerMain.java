package com.crossserver;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Type;
import com.crossserver.models.*;
import com.crossserver.models.Notification.UDPNotifier;
import com.crossserver.models.Orders.LimitOrder;
import com.crossserver.models.Orders.MarketOrder;
import com.crossserver.models.Orders.Order;
import com.crossserver.models.Orders.OrderBook;
import com.crossserver.models.Orders.StopOrder;
import com.crossserver.models.Orders.TradeHistory;
import com.crossserver.models.Session.SessionManager;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;

/*
 * The main class of the server
 */
public class CrossServerMain {

    private static final String CONFIG_FILE = "server.properties"; // Configuration file
    private static final String USERS_DB = "usersDB.json"; // User database file
    private static final String ORDER_HISTORY_DB = "orderHistoryDB.json"; // order history database file
    private static final String LIMIT_ORDER_DB = "limitDB.json"; // not executed limit order database file
    private static final String STOP_ORDER_DB = "stopDB.json"; // not executed stop order database file
    private static final String DEFAULT_FILE_PATH = "src/main/java/com/crossserver/data/"; // default file path
    private final ScheduledExecutorService DBpersistenceExecutor; // Database persistence executor: used to save the
                                                                  // databases periodically

    private final ConcurrentHashMap<String, String> usersDB; // User database : username, encrypted password

    private OrderBook orderBook;
    private static AtomicLong orderIdCounter; // Order ID counter
    private UDPNotifier notifier; // UDP notifier

    private ServerSocket serverSocket; // Server socket
    private int serverPort; // Server port
    private long maxSessionTime; // Maximum user session time

    private final ExecutorService threadPool; // Thread pool for handling user requests
    private long maxThreadPoolTerminationTime; // Maximum time to wait for the thread pool to terminate in milliseconds
    private long periodicallySaveDB; // Periodic database persistence time in milliseconds

    private final SessionManager sessionManager; // Session manager used to manage user sessions
    private final Gson gson; // Gson object used to serialize and deserialize JSON objects

    public CrossServerMain() {
        // Load the default configuration and connect to the server
        loadConfiguration();

        // Initilization of the session manager
        sessionManager = new SessionManager(maxSessionTime);
        gson = new Gson();

        // Default initialization of the server user database
        usersDB = new ConcurrentHashMap<>();
        // Default initialization of the order book
        notifier = new UDPNotifier();
        orderBook = new OrderBook(notifier);

        orderIdCounter = new AtomicLong(0);

        // thread pool initialization
        threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // single scheduled executor for the database persistence
        DBpersistenceExecutor = Executors.newSingleThreadScheduledExecutor();

        // load the server databases
        loadDatabases();

        // activate the periodic persistence of the databases
        startPeriodicPersistence();

        // Save the state of the server when it is shut down
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            // close the server socket
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("[!] Error while closing the server socket: " + e.getMessage());
            }

            // close the database persistence executor
            DBpersistenceExecutor.shutdownNow();

            // thread pool shutdown
            threadPool.shutdown();
            try {
                // Wait ""maxThreadPoolTerminationTime" milliseconds for the thread pool to
                // terminate
                if (!threadPool.awaitTermination(maxThreadPoolTerminationTime, TimeUnit.MILLISECONDS))
                    threadPool.shutdownNow();
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
            // save the server state before shutting down
            saveDatabases();
            System.out.println("Server state saved successfully");
        }));
    }

    /*
     * Return the session manager
     */
    public synchronized SessionManager getSessionManager() {
        return sessionManager;
    }

    /*
     * Periodically save the server databases according to the time specified in the
     * configuration file
     */
    public void startPeriodicPersistence() {
        DBpersistenceExecutor.scheduleAtFixedRate(() -> {
            try {
                // save the server databases
                saveDatabases();
                System.out.println("[DB] Periodic persistence completed successfully");
            } catch (Exception e) {
                System.err.println("[ERROR] Periodic persistence failed: " + e.getMessage());
            }
        }, periodicallySaveDB, periodicallySaveDB, TimeUnit.MILLISECONDS); // execute the task every
                                                                           // "periodicallySaveDB" seconds according
                                                                           // the configuration file

    }

    /*
     * Load the default configuration of server
     * 
     */
    private void loadConfiguration() {
        // Load the configuration file
        try (InputStream configFileStream = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            Properties config = new Properties();
            config.load(configFileStream);
            serverPort = Integer.parseInt(config.getProperty("port")); // extract the port from the configuration file

            // extract the maximum session time from the configuration file
            maxSessionTime = Long.parseLong(config.getProperty("sessionTime"));

            // extract the server address from the configuration file
            String serverAddress = config.getProperty("address");
            // extract the maximum thread pool size from the configuration file
            maxThreadPoolTerminationTime = Long.parseLong(config.getProperty("threadPoolTerminationTime"));
            periodicallySaveDB = Long.parseLong(config.getProperty("intervalSaveDB"));

            serverSocket = new ServerSocket(serverPort, 0, InetAddress.getByName(serverAddress)); //

        } catch (NullPointerException e) {
            System.err.println("Configuration file has not been found :" + CONFIG_FILE);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error while reading configuration file: " + e.getMessage());
            System.exit(1);
        }
    }

    /*
     * Save the server databases: the user database, the order history, the limit
     * orders and
     * the stop orders. The information are saved in JSON format periodically and
     * when the
     * server is preparing to shut down
     */
    private void saveDatabases() {
        // Save the user database
        saveToFile(USERS_DB, usersDB);
        // Save the order history
        saveToFile(ORDER_HISTORY_DB,
                Map.of("orderIdCounter", orderIdCounter.get(), "trades", orderBook.getOrderHistory()));
        // Save the limit orders (bid and ask)
        saveToFile(LIMIT_ORDER_DB, Map.of("limitAskOrders", orderBook.getLimitAskOrders(), "limitBidOrders",
                orderBook.getLimitBidOrders()));
        // Save the stop orders (bid and ask)
        saveToFile(STOP_ORDER_DB,
                Map.of("stopAskOrders", orderBook.getStopAskOrders(), "stopBidOrders", orderBook.getStopBidOrders()));
    }

    /*
     * Save the data structure in the file in JSON format
     */
    private synchronized void saveToFile(String filename, Map<String, ?> data) {
        File dbDirectory = new File(DEFAULT_FILE_PATH);
        // check if the directory exists, otherwise create it
        if (!dbDirectory.exists()) {
            dbDirectory.mkdirs();
        }
        File dataFile = new File(dbDirectory, filename);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile))) {
            // write the information in the data structure to the file in JSON format
            gson.toJson(data, writer);
        } catch (IOException e) {
            System.err.println("Error saving data to file: " + e.getMessage());
        }
    }

    /*
     * Load the server databases: the user database, the order history, the limit
     * orders and
     * the stop orders. The information are loaded from the JSON files when the
     * server is
     * started
     */
    private void loadDatabases() {
        // Save the user database
        loadUserDB(USERS_DB);
        // Save the order history
        loadOrderHistory(ORDER_HISTORY_DB);
        // Save the limit orders (bid and ask)
        loadLimitOrders(LIMIT_ORDER_DB);
        // Save the stop orders (bid and ask)
        loadStopOrders(STOP_ORDER_DB);
    }

    /*
     * Load the user database from the file in JSON format
     */
    private void loadUserDB(String filename) {
        String filePath = new StringBuilder(DEFAULT_FILE_PATH).append(filename).toString();
        File userFile = new File(filePath);
        // check if the file exists otherwise create a new file
        if (!userFile.exists()) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
            // let create a new file for the user database
            saveToFile(filename, Collections.emptyMap());
            return;
        }
        // load the user database from the file
        try (BufferedReader reader = new BufferedReader(new FileReader(userFile))) {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();

            Map<String, String> map = gson.fromJson(reader, type);
            if (map != null) {
                synchronized (usersDB) {
                    usersDB.putAll(map); // copy data to the usersDB
                }
                System.out.println("[Configuration loading] State loaded successfully from " + filename);
            }
        } catch (FileNotFoundException e) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
        } catch (JsonSyntaxException e) {
            System.err.println("[Configuration loading] Error loading state from file: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[Configuration loading] Unexpected error while loading state: " + e.getMessage());
        }
    }

    /*
     * Load the order history from the file in JSON format
     */

    private void loadOrderHistory(String filename) {

        String filePath = new StringBuilder(DEFAULT_FILE_PATH).append(filename).toString();
        File orderHistoryFile = new File(filePath);
        // check if the file exists otherwise create a new file
        if (!orderHistoryFile.exists()) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
            // let create a new file for the user database
            saveToFile(filename, Map.of("orderIdCounter", 0L, "trades", Collections.EMPTY_LIST));
            return;
        }
        // load the order history from the file
        try (BufferedReader reader = new BufferedReader(new FileReader(orderHistoryFile))) {

            JsonObject jsonObjectFile = gson.fromJson(reader, JsonObject.class);

            if (jsonObjectFile == null || !jsonObjectFile.has("trades")) {
                throw new FileNotFoundException();
            }

            // update the order history book
            if (jsonObjectFile.has("trades")) {
                // convert the JSON array of trades into a JsonArray
                JsonArray orderArray = jsonObjectFile.getAsJsonArray("trades");

                // set the order history of the order book
                ConcurrentLinkedQueue<Order> orderHistory = new ConcurrentLinkedQueue<>();
                Order order = null;
                long maxOrderId = 0;
                for (JsonElement orderElement : orderArray) {
                    JsonObject orderJson = orderElement.getAsJsonObject();
                    String orderType = orderJson.get("orderType").getAsString();

                    switch (orderType) {
                        case "market":
                            order = gson.fromJson(orderJson, MarketOrder.class);
                            break;
                        case "limit":
                            order = gson.fromJson(orderJson, LimitOrder.class);
                            break;
                        case "stop":
                            order = gson.fromJson(orderJson, StopOrder.class);
                            break;
                    }
                    if (order != null) {
                        orderHistory.add(order);
                    }
                    // extract the maximum order id in the order history
                    maxOrderId = Math.max(maxOrderId, order.getOrderId());
                }
                // set the order id counter to the maximum order id in the order history plus
                // one
                orderIdCounter = new AtomicLong(maxOrderId + 1);
                // set the order history of the order book
                orderBook.setOrderHistory(orderHistory);
            }

            // set the order id counter
            if (jsonObjectFile.has("orderIdCounter")) {
                orderIdCounter = new AtomicLong(jsonObjectFile.get("orderIdCounter").getAsLong());
            }

            System.out.println("[Configuration loading] State loaded successfully from " + filename);

        } catch (FileNotFoundException e) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
            orderIdCounter = new AtomicLong(0);
        } catch (JsonSyntaxException e) {
            System.err.println("[Configuration loading] Error loading state from file: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[Configuration loading] Unexpected error while loading state: " + e.getMessage());
        }
    }

    /*
     * Load the limit (bid and ask) orders from the file in JSON format
     */
    private void loadLimitOrders(String filename) {

        String filePath = new StringBuilder(DEFAULT_FILE_PATH).append(filename).toString();
        File limitOrderFile = new File(filePath);
        // check if the file exists otherwise create a new file
        if (!limitOrderFile.exists()) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
            // let create a new file for the user database
            saveToFile(filename,
                    Map.of("limitAskOrders", Collections.EMPTY_MAP, "limitBidOrders", Collections.EMPTY_MAP));
            return;
        }
        // load the limit orders from the file
        try (BufferedReader reader = new BufferedReader(new FileReader(limitOrderFile))) {

            Type type = new TypeToken<Map<String, Object>>() {
            }.getType();
            Map<String, Object> map = gson.fromJson(reader, type);

            if (map != null) {
                // define the type of the limit order list for the json deserialization
                Type orderListType = new TypeToken<ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>>>() {
                }.getType();

                // used to determine the maximum order id in the limit orders
                long maxOrderId = 0;

                // load the limit ask orders
                if (map.containsKey("limitAskOrders")) {
                    // extract the limit ask orders from the JSON file
                    ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitAskOrdersFromJsonFile = gson
                            .fromJson(gson.toJson(map.get("limitAskOrders")), orderListType);

                    synchronized (limitAskOrdersFromJsonFile) {
                        for (Map.Entry<Long, ConcurrentLinkedQueue<LimitOrder>> entry : limitAskOrdersFromJsonFile
                                .entrySet()) {
                            ConcurrentLinkedQueue<LimitOrder> orders = entry.getValue();
                            // extract the maximum order id in the limit ask orders
                            maxOrderId = Math.max(orders.stream().mapToLong(Order::getOrderId).max().orElse(0),
                                    maxOrderId);
                        }
                    }

                    orderBook.setLimitAskOrders(limitAskOrdersFromJsonFile);
                }

                // load the limit bid orders
                if (map.containsKey("limitBidOrders")) {

                    // deserialize the limit bid orders from the JSON file
                    ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> deserializedMap = gson
                            .fromJson(gson.toJson(map.get("limitBidOrders")), orderListType);

                    // create a new concurrent skip list map to store the limit bid orders
                    ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitBidOrdersFromJsonFile = new ConcurrentSkipListMap<>(
                            Comparator.reverseOrder());

                    // copy the limit bid orders from the deserialized map to the new map
                    limitBidOrdersFromJsonFile.putAll(deserializedMap);

                    synchronized (limitBidOrdersFromJsonFile) {
                        for (Map.Entry<Long, ConcurrentLinkedQueue<LimitOrder>> entry : limitBidOrdersFromJsonFile
                                .entrySet()) {
                            ConcurrentLinkedQueue<LimitOrder> orders = entry.getValue();
                            // extract the maximum order id in the limit ask orders
                            maxOrderId = Math.max(orders.stream().mapToLong(Order::getOrderId).max().orElse(0),
                                    maxOrderId);
                        }
                    }
                    orderBook.setLimitBidOrders(limitBidOrdersFromJsonFile);
                }

                // set the order id counter to the maximum order id in the limit orders plus one
                orderIdCounter = new AtomicLong(Math.max(maxOrderId, orderIdCounter.get() - 1) + 1);

                System.out.println("[Configuration loading] State loaded successfully from " + filename);
            }
        } catch (FileNotFoundException e) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
        } catch (JsonSyntaxException e) {
            System.err.println("[!] Error loading state from file: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[!] Unexpected error while loading state: " + e.getMessage());
        }
    }

    /*
     * Load the stop (bid and ask) orders from the file in JSON format
     */
    private void loadStopOrders(String filename) {

        String filePath = new StringBuilder(DEFAULT_FILE_PATH).append(filename).toString();
        File stopOrderFile = new File(filePath);
        // check if the file exists otherwise create a new file
        if (!stopOrderFile.exists()) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
            // let create a new file for the user database
            saveToFile(filename,
                    Map.of("stopAskOrders", Collections.EMPTY_MAP, "stopBidOrders", Collections.EMPTY_MAP));
            return;
        }
        // load the stop orders from the file
        try (BufferedReader reader = new BufferedReader(new FileReader(stopOrderFile))) {

            Type type = new TypeToken<Map<String, Object>>() {
            }.getType();
            Map<String, Object> map = gson.fromJson(reader, type);
            if (map != null) {
                // define the type of the limit order list for the json deserialization
                Type orderListType = new TypeToken<ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>>>() {
                }.getType();

                // used to determine the maximum order id in the limit orders
                long maxOrderId = 0;

                // load the stop ask orders
                if (map.containsKey("stopAskOrders")) {
                    // extract the limit ask orders from the JSON file
                    ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopAskOrdersFromJsonFile = gson
                            .fromJson(gson.toJson(map.get("stopAskOrders")), orderListType);

                    synchronized (stopAskOrdersFromJsonFile) {
                        for (Map.Entry<Long, ConcurrentLinkedQueue<StopOrder>> entry : stopAskOrdersFromJsonFile
                                .entrySet()) {
                            ConcurrentLinkedQueue<StopOrder> orders = entry.getValue();
                            // extract the maximum order id in the limit ask orders
                            maxOrderId = Math.max(orders.stream().mapToLong(Order::getOrderId).max().orElse(0),
                                    maxOrderId);
                        }
                    }

                    orderBook.setStopAskOrders(stopAskOrdersFromJsonFile);
                }

                // load the stop bid orders
                if (map.containsKey("stopBidOrders")) {
                    // extract the limit ask orders from the JSON file
                    ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopBidOrdersFromJsonFile = gson
                            .fromJson(gson.toJson(map.get("stopBidOrders")), orderListType);

                    synchronized (stopBidOrdersFromJsonFile) {
                        for (Map.Entry<Long, ConcurrentLinkedQueue<StopOrder>> entry : stopBidOrdersFromJsonFile
                                .entrySet()) {
                            ConcurrentLinkedQueue<StopOrder> orders = entry.getValue();
                            // extract the maximum order id in the limit ask orders
                            maxOrderId = Math.max(orders.stream().mapToLong(Order::getOrderId).max().orElse(0),
                                    maxOrderId);
                        }
                    }

                    orderBook.setStopBidOrders(stopBidOrdersFromJsonFile);
                }

                // set the order id counter to the maximum order id in the limit orders plus one
                orderIdCounter = new AtomicLong(Math.max(maxOrderId, orderIdCounter.get() - 1) + 1);

                System.out.println("[Configuration loading] State loaded successfully from " + filename);
            }
        } catch (FileNotFoundException e) {
            System.out.println(
                    "[Configuration loading] No previous state file found, starting fresh of \"" + filename + "\"");
        } catch (JsonSyntaxException e) {
            System.err.println("[!] Error loading state from file: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[!] Error loading state from file: " + e.getMessage());
        }
    }

    /*
     * Hash the password using the SHA-256 algorithm and return the hashed password
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            // convert the byte array to a hexadecimal string
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

    /*
     * Register a new user the method returns a JSON string containing the response
     * code and
     * the error message to be forwarded to the client
     */
    public String register(JsonObject request) {
        // check if the request contains the operation and values fields
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }
        JsonObject values = request.get("values").getAsJsonObject();

        // check if the request contains the username and password fields in the values
        // object
        if (!values.has("username") || !values.has("password")) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameters"));
        }

        String username = values.get("username").getAsString();
        String password = values.get("password").getAsString();

        // check if the username and password are empty
        if (username.isEmpty() || password.isEmpty()) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "User parameter not found"));
        }

        // check if the password matches the pattern
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

    /*
     * Check if the password matches the regular expression pattern
     */
    private boolean checkPassword(String password) {
        /*
         * ^ represents starting character of the string. (?=.*[0-9]) represents a digit
         * must
         * occur at least once. (?=.*[a-z]) represents a lower case alphabet must occur
         * at least
         * once. (?=.*[A-Z]) represents an upper case alphabet that must occur at least
         * once.
         * (?=\\S+$) white spaces don’t allowed in the entire string. .{8, 20}
         * represents at least
         * 8 characters and at most 20 characters. $ represents the end of the string.
         */
        String pattern = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\\S+$).{8,20}$";
        return password.matches(pattern);
    }

    /*
     * Update the user credentials the method returns a JSON string containing the
     * response
     */
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

        // check if the new password matches the pattern
        if (!checkPassword(newPassword)) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Invalid new password"));
        }
        // check if the username exists
        if (user_password == null) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Non-existent username"));
        }
        // check if the old password matches the stored password
        if (!user_password.equals(hashPassword(oldPassword))) {
            return gson.toJson(Map.of("response", 102, "errorMessage", "Username/old password mismatch"));
        }
        // check if the new password is equal to the old password
        if (newPassword.equals(oldPassword)) {
            return gson.toJson(Map.of("response", 103, "errorMessage", "New password equal to the old one"));
        }

        if (sessionManager.isUserLoggedIn(username)) {
            return gson.toJson(Map.of("response", 104, "errorMessage", "User currently logged in"));
        }

        usersDB.put(username, hashPassword(newPassword)); // update the user's password

        return gson.toJson(Map.of("response", 100, "errorMessage", "Password updated successfully"));
    }

    /*
     * User login the method returns a map containing the response code, the error
     * message and the maximum user session which will be forwarded to the client,
     * moreover it saves the username for let the server "UserHandler" to manage the
     * user
     * connection and update the server when something happends to the user
     * connection.
     * The "UserHandler" will send the response to the client according to the
     * format in the "handleRequest" method
     */

    public Map<String, Object> login(JsonObject request, UserHandler activeConnection) {

        if (!request.has("operation") || !request.has("values")) {
            return Map.of("response", 103, "errorMessage", "Missing parameters");
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("username") || !values.has("password")) {
            return Map.of("response", 103, "errorMessage", "Missing parameters");
        }

        String username = values.get("username").getAsString();
        String password = values.get("password").getAsString();

        String storedPassword = usersDB.get(username);
        String checkPassword = hashPassword(password);

        // check if the user exists or not
        if (storedPassword == null) {
            return Map.of("response", 101, "errorMessage", "Non existent username");
        }

        // check if the user is already logged in
        if (sessionManager.isUserLoggedIn(username)) {
            return Map.of("response", 102, "errorMessage", "User already logged in");
        }

        // check if the password is correct
        if (!storedPassword.equals(checkPassword)) {
            return Map.of("response", 101, "errorMessage", "Username/password mismatch");
        }

        // Start user session
        sessionManager.loginUser(username);

        // // Save the user connection
        // activeUserConnections.put(username, activeConnection);

        return Map.of("userId", username, "session", maxSessionTime, "response", 100, "errorMessage", "OK");
    }

    /*
     * User logout the method returns a JSON string containing the response code and
     * the error message to be forwarded to the client
     */
    public String logout(JsonObject request, UserHandler activeConnection) {

        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("username")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        String username = values.get("username").getAsString();

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

        // logout the user
        sessionManager.logoutUser(username);
        // activeUserConnections.remove(username);

        // unregister the user from the UDP notifier
        notifier.unregisterUdpClient(username);

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK"));
    }

    /*
     * Handle the client request to add a limit order to the order book and return a
     * JSON string containing the order ID (or -1 in case of error) to be forwarded
     * to the client
     */
    public String handleLimitOrderRequest(JsonObject request, Socket clientSocket) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderId", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("type") || !values.has("size") || !values.has("price") || !values.has("userId")
                || !values.has("udpPort")) {
            return gson.toJson(Map.of("orderId", -1)); // error: missing parameters
        }

        String type = values.get("type").getAsString();
        long size = values.get("size").getAsLong();
        long price = values.get("price").getAsLong();

        // check if the type, size and price are valid
        if ((!type.equals("bid") && !type.equals("ask")) || size <= 0 || price <= 0) {
            return gson.toJson(Map.of("orderId", -1)); // error
        }

        // limit order creation
        String userId = values.get("userId").getAsString();

        LimitOrder order = new LimitOrder(orderIdCounter.getAndIncrement(), type, size, price);
        order.setUserId(userId);

        // get the user's UDP port for notifications
        int udpPort = values.get("udpPort").getAsInt();

        // Register the user's UDP port for notifications
        notifier.registerUdpClient(userId, clientSocket.getInetAddress(), udpPort);

        orderBook.insertLimitOrder(order); // insert the order in the order book
        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity
        return gson.toJson(Map.of("orderId", order.getOrderId(), "newUserSession", updatedUserSessionTime));
    }

    /*
     * Handle the client request to add a market order to the order book and return
     * a JSON string containing the order ID (or -1 in case of error) to be
     * forwarded to the client
     */
    public String handleMarketOrderRequest(JsonObject request, Socket clientSocket) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderId", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("type") || !values.has("size") || !values.has("userId") || !values.has("udpPort")) {
            return gson.toJson(Map.of("orderId", -1)); // error: missing parameters
        }

        String type = values.get("type").getAsString();

        // market order creation
        long size = values.get("size").getAsLong();
        String userId = values.get("userId").getAsString();

        if ((!type.equals("bid") && !type.equals("ask")) || size <= 0) {
            return gson.toJson(Map.of("orderId", -1)); // error
        }
        int udpPort = values.get("udpPort").getAsInt();

        // Register the user's UDP port for notifications
        notifier.registerUdpClient(userId, clientSocket.getInetAddress(), udpPort);

        // insert the order in the order book and return its identifier
        long executedOrderid = orderBook.insertMarketOrder(orderIdCounter.getAndIncrement(), type, size, userId);

        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity
        return gson.toJson(Map.of("orderId", executedOrderid, "newUserSession", updatedUserSessionTime));

    }

    /*
     * Handle the client request to add a stop order to the order book and return a
     * JSON string containing the order ID (or -1 in case of error) to be forwarded
     * to the client
     */
    public String handleStopOrderRequest(JsonObject request, Socket clientSocket) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderId", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("type") || !values.has("size") || !values.has("price") || !values.has("userId")
                || !values.has("udpPort")) {
            return gson.toJson(Map.of("orderId", -1)); // Errore
        }

        String type = values.get("type").getAsString();
        long size = values.get("size").getAsLong();
        long price = values.get("price").getAsLong();
        String userId = values.get("userId").getAsString();

        if (!type.equals("bid") && !type.equals("ask")) {
            return gson.toJson(Map.of("orderId", -1)); // Error
        }
        int udpPort = values.get("udpPort").getAsInt();

        // Register the user's UDP port for notifications
        notifier.registerUdpClient(userId, clientSocket.getInetAddress(), udpPort);

        // stop order creation
        StopOrder stopOrder = new StopOrder(orderIdCounter.getAndIncrement(), type, size, price);
        stopOrder.setUserId(userId);

        orderBook.insertStopOrder(stopOrder); // insert the order in the order book
        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity
        return gson.toJson(Map.of("orderId", stopOrder.getOrderId(), "newUserSession", updatedUserSessionTime));
    }

    /*
     * Handle the client request to cancel an order and return a JSON string
     * containing the order ID (or -1 in case of error) to be forwarded to the
     * client
     */
    public String cancelOrder(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("orderId", -1)); // error: missing parameters
        }
        JsonObject values = request.get("values").getAsJsonObject();

        if (!values.has("orderId") || !values.has("userId")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
        }
        String userId = values.get("userId").getAsString();
        long orderId = values.get("orderId").getAsLong();

        Order order = orderBook.getOrder(orderId);
        // check if the order exists
        if (order == null)
            return gson.toJson(Map.of("response", 101, "errorMessage", "Order does not exist"));

        // get the user id of the order
        String orderUserId = order.getUserId();
        // check if the order belongs to the user
        if (orderUserId == null || !order.getUserId().equals(userId))
            return gson.toJson(Map.of("response", 101, "errorMessage", "Order belongs to different user"));
        if (order.isExecuted())
            return gson.toJson(Map.of("response", 101, "errorMessage", "Order has been executed"));

        orderBook.cancelOrder(orderId);

        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity

        return gson.toJson(Map.of("response", 100, "errorMessage", "OK", "newUserSession", updatedUserSessionTime)); // order
                                                                                                                     // has
                                                                                                                     // been
                                                                                                                     // deleted
    }

    /*
     * Handle the client request to get the trade history of a specify month and
     * year and return a JSON string containing the list of fulfilled orders to be
     * forwarded to the client
     */
    public String getPriceHistory(JsonObject request) {
        if (!request.has("operation") || !request.has("values")) {
            return gson.toJson(Map.of("response", 101, "errorMessage", "Missing parameters"));
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
        // get the trade history of the month
        ConcurrentSkipListMap<String, TradeHistory> orderHistory = orderBook.getOrderHistory(startOfMonthSeconds,
                endOfMonthSeconds);
        long updatedUserSessionTime = sessionManager.updateUserActivity(userId); // update user activity

        return gson
                .toJson(Map.of("newUserSession", updatedUserSessionTime, "month", month, "tradeHistory", orderHistory));
    }

    // Main
    public static void main(String[] args) {
        try {
            CrossServerMain server = new CrossServerMain();
            server.start();
        } catch (IOException e) {
            System.err.println("Error while starting the server " + e.getMessage());
        }
    }

}
