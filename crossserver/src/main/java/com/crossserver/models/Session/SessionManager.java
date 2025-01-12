package com.crossserver.models.Session;

import java.util.*;
import java.util.concurrent.*;

public class SessionManager {
    private final ConcurrentHashMap<String, Long> sessionMap;
    private final long sessionTimeout; // Timeout in milliseconds
    private final ScheduledExecutorService executor; // Monitor user session

    public SessionManager(long timeoutInMillis) {
        this.sessionTimeout = timeoutInMillis;
        sessionMap = new ConcurrentHashMap<>();
        executor = Executors.newSingleThreadScheduledExecutor();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdownNow();
        }));
        startSessionMonitor();
    }

    // Save user session
    public void loginUser(String username) {
        sessionMap.put(username, System.currentTimeMillis());
    }

    // Remove user session
    public void logoutUser(String username) {
        sessionMap.remove(username);
    }

    // Check if user is logged in
    public boolean isUserLoggedIn(String username) {
        return sessionMap.containsKey(username);
    }

    /*
     * Update user activity
     * Return the current time if user is logged in
     * Return -1 if user is not logged in
     * 
     */

    public long updateUserActivity(String username) {
        long currentTime = System.currentTimeMillis();
        if (!sessionMap.containsKey(username))
            return -1;
        sessionMap.put(username, currentTime);
        return currentTime;
    }

    // Monitor user each user session
    private void startSessionMonitor() {
        executor.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            sessionMap.entrySet().removeIf(entry -> {
                if (currentTime - entry.getValue() > sessionTimeout) {
                    System.out.println("[Session user manager] User " + entry.getKey() + " has been removed due to inactivity");
                    return true;
                }
                return false;
            });
        }, 0, 10, TimeUnit.SECONDS); // TODO: TEMPORANEO PER IL TESTING checks every 10 seconds
    }
}
