package com.crossclient;

import java.io.*;
import java.net.*;

import com.crossclient.models.requestTypes.LoginRequest;
import com.crossclient.models.requestTypes.RegistrationRequest;
import com.crossclient.models.responseTypes.UserSessionResponse;
import com.google.gson.*;
import java.util.Properties;

public class CrossClientMain {

    private static final String CONFIG_FILE = "client.properties"; // Configuration file

    private String serverHost; // Server host
    private int serverPort; // Server port
    private Socket socket; // Socket

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
            serverHost = config.getProperty("server.host");
            serverPort = Integer.parseInt(config.getProperty("server.port"));

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

    // User registration
    private void register(BufferedReader console) throws IOException {
        System.out.print("Username: ");
        String username = console.readLine();
        System.out.print("Password: ");
        String password = console.readLine();

        String request = gson.toJson(new RegistrationRequest(username, password));
        sendRequest(request);
        output.print(request);
        handleSessionResponse();
    }

    // User login
    private void login(BufferedReader console) throws IOException {
        System.out.print("Username: ");
        String username = console.readLine();
        System.out.print("Password: ");
        String password = console.readLine();

        String request = gson.toJson(new LoginRequest(username, password));
        output.println(request);
        handleSessionResponse();

    }

    // Logout
    private void logout(BufferedReader console) throws IOException {
        output.println("{}");
        handleSessionResponse();
    }

    // Handle server response about user activity
    private void handleSessionResponse() throws IOException {
        UserSessionResponse response = gson.fromJson(input.readLine(),
                UserSessionResponse.class);

        System.out.println(response.toString()); // print the server response
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

  }
