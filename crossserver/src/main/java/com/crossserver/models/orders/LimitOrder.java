package com.crossserver.models.Orders;

/*
 * This class represents a limit order.
 */
public class LimitOrder extends Order {

    public LimitOrder(long orderId, String type, long size, long limitPrice) {
        super(orderId, type, size, limitPrice);
        this.orderType = "limit";
    }
}
