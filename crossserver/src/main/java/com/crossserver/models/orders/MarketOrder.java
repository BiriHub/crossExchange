package com.crossserver.models.Orders;

public class MarketOrder extends Order {

    public MarketOrder(long orderId, String type, long size,long price) {
        super(orderId, type, size,price);
        this.orderType = "market";
    }
}
