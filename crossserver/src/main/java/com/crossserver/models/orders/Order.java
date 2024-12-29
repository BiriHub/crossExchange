package com.crossserver.models.orders;

public class Order {
    private static long orderIdCounter = 0; // Order ID counter
    private final long orderId;
    private final String type; // Order type: bid or ask
    private final String orderType; // Order type: limit, market or stop 
    private long size; // Order size
    private final long price; // Order price 
    private final long timestamp; // Order timestamp
    private final long userId; // User ID
    private boolean isClosed; // Order status

    // Constructor to read the order from the database
    public Order(long orderId, String type, String orderType, long size, long price, long timestamp, long userId) {
        this.orderId = orderId;
        this.type = type;
        this.orderType = orderType;
        this.size = size;
        this.price = price;
        this.timestamp = timestamp;
        this.userId = userId;
    }

    public Order(String type, String orderType, long size, long price, long timestamp, long userId) {
        this(++orderIdCounter, type, orderType, size, price, timestamp, userId);
    }

    public long getOrderId() {
        return orderId;
    }

    public String getType() {
        return type;
    }

    public String getOrderType() {
        return orderType;
    }

    public long getSize() {
        return size;
    }

    public long getPrice() {
        return price;
    }

    public long getTimestamp() {
        return timestamp;
    }
    
    public long getUserId() {
        return userId;
    }
    public boolean isClosed() {
        return isClosed;
    }
    public void setClosed(boolean isClosed) {
        this.isClosed = isClosed;
    }
    public void setSize(long size) {
        this.size = size;
    }
    
}
