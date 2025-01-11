package com.crossserver.models.orders;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class OrderBook {
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitAskOrders; // sell
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitBidOrders; // buy
    private final ConcurrentLinkedQueue<Order> orderHistory; // order history

    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopBidOrders;
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopAskOrders;

    public OrderBook() {
        this.limitAskOrders = new ConcurrentSkipListMap<>();
        this.limitBidOrders = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.orderHistory = new ConcurrentLinkedQueue<>();
        this.stopBidOrders = new ConcurrentSkipListMap<>();
        this.stopAskOrders = new ConcurrentSkipListMap<>();
    }

    // Insert a stop order in the order book according to its type (bid or ask)
    public long insertStopOrder(StopOrder order) {
        if (order.getType().equals("bid")) {
            stopBidOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        } else if (order.getType().equals("ask")) {
            stopAskOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        }
        return order.getOrderId();
    }

    private void activateStopOrders(long currentPrice) {
        // Activate stop-buy
        stopBidOrders.headMap(currentPrice, true).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                Order stopOrder = queue.poll();
                MarketOrder marketOrder = new MarketOrder(stopOrder.getOrderId(), stopOrder.getType(),
                        stopOrder.getSize(),
                        currentPrice);
                marketOrder.setUserId(stopOrder.getUserId());

                long fulfilledOrderId = insertMarketOrder(marketOrder.getOrderId(), marketOrder.getType(),
                        marketOrder.getSize(),
                        stopOrder.getUserId());
                if (fulfilledOrderId != -1) {
                    // stop order has been executed with no errors
                    stopOrder.setTimestamp(System.currentTimeMillis() / 1000L);
                    orderHistory.offer(stopOrder);

                }
            }
            stopBidOrders.remove(price); // Remove the stop order
        });

        // Activate stop-sell
        stopAskOrders.tailMap(currentPrice, false).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                Order stopOrder = queue.poll();
                MarketOrder marketOrder = new MarketOrder(stopOrder.getOrderId(), stopOrder.getType(),
                        stopOrder.getSize(),
                        currentPrice);
                marketOrder.setUserId(stopOrder.getUserId());

                long fulfilledOrderId = insertMarketOrder(marketOrder.getOrderId(), marketOrder.getType(),
                        marketOrder.getSize(),
                        stopOrder.getUserId());
                if (fulfilledOrderId != -1) {
                    // stop order has been executed correttamente
                    stopOrder.setTimestamp(System.currentTimeMillis() / 1000L);
                    orderHistory.offer(stopOrder);
                }

            }
            stopAskOrders.remove(price); // Remove the stop order
        });
    }

    // Insert a limit order in the order book according to its type (bid or ask),
    // return the order ID
    public long insertLimitOrder(LimitOrder order) {
        if (order.getType().equals("bid"))
            // Add a new bid order to the limit order
            limitBidOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        else
            // Add an ask order to the limit order book
            limitAskOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);

        return order.getOrderId();
    }

    // Execute a market order
    public long insertMarketOrder(long orderId, String type, long size, String userId) {
        if (type.equals("bid"))
            return matchBidOrder(orderId, type, size, userId);
        else
            return matchAskOrder(orderId, type, size, userId);
    }

    // Esegue un ordine di acquisto contro il book di vendita
    // Execute a buy market order against the sell limit book
    // TODO : to test
    public long matchBidOrder(long orderId, String type, long size, String userId) {

        // Copy the ask book in order to save the original state of the book in case of
        // impossibility to fulfill the order

        LimitOrder checkMatchLimitOrder = limitAskOrders.get(limitAskOrders.firstKey()).peek();

        // if the sell limit book is empty or the first order is not enough to fulfill
        // the market order it returns -1 (error)
        if (checkMatchLimitOrder == null || checkMatchLimitOrder.getSize() < size) {
            return -1;
        }
        limitAskOrders.pollFirstEntry(); // remove the first entry of the sell limit book
        long price = checkMatchLimitOrder.getPrice();

        // set the timestamp of the executed order to the current time to notify the
        // order has been executed
        checkMatchLimitOrder.setTimestamp(System.currentTimeMillis() / 1000L);
        addOrderHistory(checkMatchLimitOrder); // add the limit order to the order history

        // Activate stop orders when the price changes
        activateStopOrders(price);
        // finally create a new market order in order to add it to the order history
        MarketOrder markerOrder = new MarketOrder(orderId, type, size, price);
        markerOrder.setUserId(userId);

        return addOrderHistory(markerOrder);
    }

    // Add an order to the order history and return the order ID
    public long addOrderHistory(Order order) {
        orderHistory.offer(order);
        return order.getOrderId();
    }

    // Execute a sell order against the buy book
    // TODO : to test
    public long matchAskOrder(long orderId, String type, long size, String userId) {

        LimitOrder checkMatchLimitOrder = limitBidOrders.get(limitBidOrders.firstKey()).peek();

        // if the buy limit book is empty or the first order is not enough to fulfill
        // the market order it returns -1 (error)
        if (checkMatchLimitOrder == null || checkMatchLimitOrder.getSize() < size) {
            return -1;
        }
        limitBidOrders.pollFirstEntry(); // remove the first entry of the buy limit book
        long price = checkMatchLimitOrder.getPrice();

        // set the timestamp of the executed order to the current time to notify the
        // order has been executed
        checkMatchLimitOrder.setTimestamp(System.currentTimeMillis() / 1000L);
        addOrderHistory(checkMatchLimitOrder); // add the limit order to the order history

        // Activate stop orders when the price changes
        activateStopOrders(price);
        // finally create a new market order in order to add it to the order history
        MarketOrder markerOrder = new MarketOrder(orderId, type, size, price);
        markerOrder.setUserId(userId);
        return addOrderHistory(markerOrder);

    }

    // Cancel an order from the order book
    public long cancelOrder(long orderId) {
        if (cancelOrderFromBook(limitAskOrders, orderId))
            return orderId;
        if (cancelOrderFromBook(limitBidOrders, orderId))
            return orderId;
        if (cancelOrderFromBook(stopAskOrders, orderId))
            return orderId;
        if (cancelOrderFromBook(stopBidOrders, orderId))
            return orderId;
        return -1; // Return -1 if the order was not found in any book
    }

    private boolean cancelOrderFromBook(
            ConcurrentSkipListMap<Long, ? extends ConcurrentLinkedQueue<? extends Order>> book, long orderId) {
        AtomicBoolean removed = new AtomicBoolean(false);
        book.values().forEach(queue -> {
            if (queue.removeIf(order -> order.getOrderId() == orderId)) {
                removed.set(true);
            }
        });
        return removed.get();
    }

    public Order getOrder(long orderId) {
        Order order = findOrderInBook(limitAskOrders, orderId);
        if (order != null)
            return order;

        order = findOrderInBook(limitBidOrders, orderId);
        if (order != null)
            return order;

        order = findOrderInBook(stopAskOrders, orderId);
        if (order != null)
            return order;

        return findOrderInBook(stopBidOrders, orderId);
    }

    // Find an order in the specified data structure given its ID , return the order
    // if found, null otherwise
    private Order findOrderInBook(ConcurrentSkipListMap<Long, ? extends ConcurrentLinkedQueue<? extends Order>> book,
            long orderId) {
        for (ConcurrentLinkedQueue<? extends Order> queue : book.values()) {
            for (Order order : queue) {
                if (order.getOrderId() == orderId) {
                    return order;
                }
            }
        }
        return null;
    }

    public ConcurrentSkipListMap<String, TradeHistory> getOrderHistory(long startOfMonth,
            long endOfMonth) {
        List<Order> orderHistoryByMonth;
        Map<Long, List<Order>> ordersPerDay;
        synchronized (orderHistory) {
            orderHistoryByMonth = orderHistory.stream().filter(order -> {
                long orderDateTimestamp = order.getTimestamp();
                return orderDateTimestamp >= startOfMonth && orderDateTimestamp <= endOfMonth;
            }).sorted(Comparator.comparing(ord -> ord.getTimestamp()))
                    .collect(Collectors.toList());
            ordersPerDay = orderHistoryByMonth.stream().collect(Collectors.groupingBy(Order::getTimestamp));
        }
        ConcurrentSkipListMap<String,TradeHistory> response = new ConcurrentSkipListMap();
        synchronized (ordersPerDay) {

            for (Map.Entry<Long, List<Order>> order : ordersPerDay.entrySet()) {
                // String date = LocalDate.ofEpochDay(order.getKey()).toString(); 
                long openingPrice = order.getValue().get(0).getPrice();
                long closingPrice = order.getValue().get(order.getValue().size() - 1).getPrice();
                long highPrice = order.getValue().stream().mapToLong(Order::getPrice).max().getAsLong();
                long lowPrice = order.getValue().stream().mapToLong(Order::getPrice).min().getAsLong();
                int dayOfMonth = LocalDate.ofEpochDay(order.getKey()).getDayOfMonth();

                TradeHistory tradeHistory = new TradeHistory(dayOfMonth, openingPrice, closingPrice, highPrice, lowPrice, order.getValue());
                response.put(Integer.toString(dayOfMonth), tradeHistory);
            }
        }

        return response;

    }
}