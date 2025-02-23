package com.crossserver.models;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import com.crossserver.CrossServerMain;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/*
 * This class is responsible for handling the client requests and sending the responses back to the client.
 */
public class UserHandler implements Runnable {

    private final Socket clientSocket; // client socket
    private BufferedReader input; // input stream
    private PrintWriter output; // output stream

    private final Gson gson; // JSON parser
    private final CrossServerMain server; // reference to server instance
    private String username; // reference of the username of the client currently logged in

    public UserHandler(Socket clientSocket, CrossServerMain server) {
        this.clientSocket = clientSocket;
        this.server = server;
        gson = new Gson();
        this.username = null;
    }

    @Override
    public void run() {
        // try with resources to automatically close the streams and socket
        try (BufferedReader input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter output = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String request;
            // read the client requests and send the responses back to the client until the
            // client disconnects or the thread is interrupted
            while (!Thread.currentThread().isInterrupted() && !clientSocket.isClosed()
                    && (request = input.readLine()) != null) {
                // parse the client JSON request
                JsonObject jsonRequest = gson.fromJson(request, JsonObject.class);
                // handle the client request
                String response = handleRequest(jsonRequest);
                output.println(response);
            }
        } catch (IOException e) {
            System.err.println("[!]Client connection error : " + e.getMessage());
        } finally {
            try {
                if (!clientSocket.isClosed())
                    clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error while closing the connection: " + e.getMessage());
            }
            server.getSessionManager().logoutUser(username); // remove the user session from the session manager if the
        }
    }

    /*
     * Handle the client request and return the response to the client
     */
    private synchronized String handleRequest(JsonObject request) {
        try {
            // check if the request has the operation parameter
            if (!request.has("operation")) {
                return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameter"));
            }
            String operation = request.get("operation").getAsString();

            // select the method according to the operation
            switch (operation) {
                case "register": // register
                    return server.register(request);
                case "updateCredentials": // update credentials
                    return server.updateCredentials(request);
                case "login": // Login
                    Map<String, Object> loginResponse = new HashMap<>(server.login(request, this));

                    // if the login was successful, store the username in the session
                    if (loginResponse.containsKey("userId")) {
                        username = loginResponse.get("userId").toString(); // store the username in the session
                        loginResponse.remove("userId"); // remove the username from the response
                    }
                    return gson.toJson(loginResponse); // remove username from response and return the formatted
                                                       // response to the client

                case "logout": // Logout
                    String logoutResponse = server.logout(request, this);
                    username = null; // remove the username from the session
                    return logoutResponse;

                case "insertLimitOrder": // add limit order
                    return server.handleLimitOrderRequest(request, clientSocket);
                case "insertMarketOrder": // add market order
                    return server.handleMarketOrderRequest(request, clientSocket);
                case "insertStopOrder": // add stop order
                    return server.handleStopOrderRequest(request, clientSocket);
                case "cancelOrder": // cancel order
                    return server.cancelOrder(request);
                case "getPriceHistory": // get order book history
                    return server.getPriceHistory(request);
                default: // error
                    return gson.toJson(Map.of("response", -1, "errorMessage", "Operation not recognized"));
            }
        } catch (Exception e) { // catch any exception and return an internal error message to the client 
            return gson.toJson(Map.of("response", -1, "errorMessage", "Internal server error: " + e.getMessage()));
        }
    }

}
