package com.crossserver.models.Orders;

/*
 * This class represents a stop order.
 */
public class StopOrder extends Order {

    public StopOrder(long orderId, String type, long size, long stopPrice) {
        super(orderId, type, size, stopPrice);
        this.orderType = "stop";
    }

}
