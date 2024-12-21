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

    private final Socket socket;
    private BufferedReader input;
    private PrintWriter output;
    private final Gson gson;
    private final CrossServerMain server;

    public UserHandler(Socket socket, CrossServerMain server) {
        this.socket = socket;
        this.server = server;
        gson = new Gson();
    }

    @Override
    public void run() {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter output = new PrintWriter(socket.getOutputStream(), true)) {

            String request;
            while ((request = input.readLine()) != null) {
                JsonObject jsonRequest = gson.fromJson(request, JsonObject.class);
                String response = handleRequest(jsonRequest);
                output.println(response);
            }
        } catch (IOException e) {
            System.err.println("Errore con il client: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Errore nella chiusura della connessione: " + e.getMessage());
            }
        }
    }

    private String handleRequest(JsonObject request) {
        try {
            // TODO finish to complete the handle request
            // TODO complete
            if (!request.has("operation")) {
                return gson.toJson("{\"response\":103, \"errorMessage\": \"Operation not found \"}");
            }
            String operation = request.get("operation").getAsString();

            switch (operation) {
                case "register": // Registrazione
                    return server.register(request);
                case "updateCredentials": // Aggiornamento credenziali
                    return server.updateCredentials(request);
                case "login": // Login
                    return server.login(request);
                case "logout": // Logout
                    return server.logout(request);
                // case "3": // Inserimento Limit Order
                //     return handleLimitOrder(request);
                // case "4": // Inserimento Market Order
                //     return handleMarketOrder(request);
                // case "5": // Inserimento Stop Order
                //     return handleStopOrder(request);
                // case "6": // Cancellazione Ordine
                //     return cancelOrder(request);
                // case "7": // Storico Prezzi
                //     return getPriceHistory(request);
                default:
                    return gson.toJson(Map.of("response", -1, "errorMessage", "Operazione non riconosciuta"));
            }
        } catch (Exception e) {
            return gson.toJson(Map.of("response", -1, "errorMessage", "Errore interno del server: " + e.getMessage()));
        }
    }

}
