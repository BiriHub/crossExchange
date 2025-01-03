package com.crossserver.models.orders;

public class LimitOrder extends Order {

    public LimitOrder(long orderId, String type, long size, long limitPrice) {
        super(orderId, type, size, limitPrice);
        this.orderType = "limit";
    }

}
