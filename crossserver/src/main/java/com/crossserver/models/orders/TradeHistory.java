package com.crossserver.models.Orders;
/*
 * Represents the trade history of the market for a specific day
 */

import java.util.ArrayList;
import java.util.List;

public class TradeHistory {
    private final int numberOfDay; // number of the day

    private long openingPrice; // Opening price of the day
    private long closingPrice; // Closing price of the day
    private long highestPrice; // Highest price of the day
    private long lowestPrice; // Lowest price of the day
    private final ArrayList<Order> fulfilledOrders; // Fulfilled orders of the day

    public TradeHistory(int numberOfDay,long openingPrice, long closingPrice, long highestPrice, long lowestPrice,List<Order> orders){
        this.numberOfDay = numberOfDay;
        this.openingPrice = openingPrice;
        this.closingPrice = closingPrice;
        this.highestPrice = highestPrice;
        this.lowestPrice = lowestPrice;
        this.fulfilledOrders = new ArrayList<>();
        this.fulfilledOrders.addAll(orders);
    }

    public int getNumberOfDay() {
        return numberOfDay;
    }

    public long getOpeningPrice() {
        return openingPrice;
    }

    public void setOpeningPrice(long openingPrice) {
        this.openingPrice = openingPrice;
    }

    public long getClosingPrice() {
        return closingPrice;
    }

    public void setClosingPrice(long closingPrice) {
        this.closingPrice = closingPrice;
    }

    public long getHighestPrice() {
        return highestPrice;
    }

    public void setHighestPrice(long highestPrice) {
        this.highestPrice = highestPrice;
    }

    public long getLowestPrice() {
        return lowestPrice;
    }

    public void setLowestPrice(long lowestPrice) {
        this.lowestPrice = lowestPrice;
    }

    public ArrayList<Order> getFulfilledOrders() {
        return fulfilledOrders;
    }

    
}
