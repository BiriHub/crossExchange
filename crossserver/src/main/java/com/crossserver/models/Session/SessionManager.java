package com.crossserver.models.Session;

import java.util.*;
import java.util.concurrent.*;

public class SessionManager {
    private final ConcurrentHashMap<String, Long> sessionMap;
    private final long sessionTimeout; // Timeout in millisecondi

    public SessionManager(long timeoutInMillis) {
        this.sessionTimeout = timeoutInMillis;
        sessionMap = new ConcurrentHashMap<>();
        startSessionMonitor();
    }

    // Save user session 
    public void loginUser(String username) {
        sessionMap.put(username, System.currentTimeMillis());
        // System.out.println("User " + username + "has been logged in");
    }

    // Remove user session
    public void logoutUser(String username) {
        sessionMap.remove(username);
        // System.out.println("User " + username+ "has been logged out");
    }

    // Check if user is logged in
    public boolean isUserLoggedIn(String username) {
        return sessionMap.containsKey(username);
    }

    // Update user activity
    public void updateUserActivity(String username) {
        if (sessionMap.containsKey(username)) {
            sessionMap.put(username, System.currentTimeMillis());
            // System.out.println("User " + username + " activity updated");
        }
    }

    // Monitor user each user session
    private void startSessionMonitor() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            sessionMap.entrySet().removeIf(entry -> {
                if (currentTime - entry.getValue() > sessionTimeout) {
                    System.out.println("User " + entry.getKey() + " has been removed due to inactivity");
                    return true; 
                }
                return false;
            });
        }, 0, 60, TimeUnit.SECONDS); // checks every 60 seconds
    }
}
