package com.crossserver.models.Orders;

public class StopOrder extends Order {

    public StopOrder(long orderId, String type, long size, long stopPrice) {
        super(orderId, type, size, stopPrice);
        this.orderType = "stop";
    }

}
