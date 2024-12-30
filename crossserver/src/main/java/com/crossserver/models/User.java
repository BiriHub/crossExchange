package com.crossserver.models;

public class User {
    private static long userIdCounter = 0; // User ID counter
    private final long userId;
    private final String username; 
    private String encryptedPassword;

    public User(String username, String password) {
        this.username = username;
        this.encryptedPassword = password;
        this.userId = userIdCounter++;
    }

    public static long getUserIdCounter() {
        return userIdCounter;
    }

    public static void setUserIdCounter(long userIdCounter) {
        User.userIdCounter = userIdCounter;
    }

    public long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    



}
