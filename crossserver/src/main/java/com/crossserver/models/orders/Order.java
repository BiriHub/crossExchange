package com.crossserver.models.Orders;

/*
 * This class represents an order in the order book
 */
public abstract class Order {
    protected final long orderId; // Order unique ID
    protected final String type; // Order type: bid or ask
    protected long size; // Order size
    protected String orderType; // Order type: market,limit or stop
    protected long timestamp; // Order timestamp: when the order has been closed
    protected final long price; // Order price: price at which the order has been closed
    private String userId; // User ID: user who placed the order

    public Order(long orderId, String type, long size, long price) {
        this.orderId = orderId;
        this.type = type;
        this.size = size;
        this.price = price;
        this.timestamp = 0;
    }
    // Check if the order has been executed
    public boolean isExecuted() {
        return timestamp != 0;
    }

    public String toJsonString() {
        return "{" + "\"orderId\":" + orderId + ",\"type\":\"" + type + "\",\"size\":" + size + ",\"orderType\":\""
                + orderType + "\",\"price\":" + price + ",\"timestamp\":" + timestamp + "}";
    }
    
    public long getOrderId() {
        return orderId;
    }

    public String getType() {
        return type;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getPrice() {
        return price;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrderType() {
        return orderType;
    }



}
