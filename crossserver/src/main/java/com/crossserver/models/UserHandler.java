package com.crossserver.models;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;

import com.crossserver.CrossServerMain;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class UserHandler implements Runnable {

    private final Socket clientSocket;
    private BufferedReader input;
    private PrintWriter output;
    private final Gson gson;
    private final CrossServerMain server;
    private String username;

    public UserHandler(Socket clientSocket, CrossServerMain server) {
        this.clientSocket = clientSocket;
        this.server = server;
        gson = new Gson();
        this.username = null;
    }

    @Override
    public void run() {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter output = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String request;
            while (!Thread.currentThread().isInterrupted() && !clientSocket.isClosed()
                    && (request = input.readLine()) != null) {
                JsonObject jsonRequest = gson.fromJson(request, JsonObject.class);
                String response = handleRequest(jsonRequest);
                output.println(response);
            }
        } catch (IOException e) {
            System.err.println("Client error : " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error while closing the connection: " + e.getMessage());
            }
            server.getSessionManager().logoutUser(username); // remove the user session from the session manager if the client disconnects abruptly without logging out
        }
    }

    private String handleRequest(JsonObject request) {
        try {
            if (!request.has("operation")) {
                return gson.toJson(Map.of("response", 103, "errorMessage", "Missing parameter"));
            }
            String operation = request.get("operation").getAsString();

            switch (operation) {
                case "register": // register
                    return server.register(request);
                case "updateCredentials": // update credentials
                    return server.updateCredentials(request);
                case "login": // Login 
                    return server.login(request, this);
                case "logout": // Logout
                    return server.logout(request,this);
                case "3": // add limit order
                    return server.handleLimitOrderRequest(request);
                case "4": // add market order
                    return server.handleMarketOrderRequest(request);
                case "5": // add stop order
                    return server.handleStopOrderRequest(request);
                case "6": // cancel order
                    return server.cancelOrder(request);
                case "7": // get order book history
                    return server.getPriceHistory(request);
                default: // error
                    return gson.toJson(Map.of("response", -1, "errorMessage", "Operation not recognized"));
            }
        } catch (Exception e) {
            return gson.toJson(Map.of("response", -1, "errorMessage", "Internal server error: " + e.getMessage()));
        }
    }

}
