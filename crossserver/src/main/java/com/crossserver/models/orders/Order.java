package com.crossserver.models.orders;

public abstract class Order {
    protected final long orderId;
    protected final String type; // Order type: bid or ask
    protected long size; // Order size
    protected String orderType; // Order type: market,limit or stop   
    protected final long price; // Order price: the price at which the order has been closed, for market orders it is the price at which the order has been matched and for limit/stop orders it is the price at which the order has been closed
    protected long timestamp; // Order timestamp: when the order has been closed
    // TODO: ask teacher if the order needs also the timestamp when it has been
    // created
    private String userId; // User ID

    // Constructor to read the order from the order history teacher format
    public Order(long orderId, String type, long size, long price) {
        this.orderId = orderId;
        this.type = type;
        this.size = size;
        this.price = price;
        this.timestamp = 0;
        this.orderType=""; // default value for a generic order
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

    public boolean isExecuted() {
        return timestamp != 0;
    }


}
