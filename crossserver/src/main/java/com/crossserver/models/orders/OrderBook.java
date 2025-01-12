package com.crossserver.models.Orders;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.crossserver.models.Notification.UDPNotifier;

public class OrderBook {
    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitAskOrders; // sell
    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitBidOrders; // buy

    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopBidOrders; // buy
    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopAskOrders; // sell

    private ConcurrentLinkedQueue<Order> orderHistory; // order history

    private final UDPNotifier UdpClientNotifier;

    public OrderBook(UDPNotifier UdpClientNotifier) {
        this.UdpClientNotifier = UdpClientNotifier;
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

    /*
     * Activate buy stop orders when the price changes
     */
    private void activateBuyStopOrders(long currentPrice) {
        // Activate stop-buy
        stopBidOrders.headMap(currentPrice, true).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                StopOrder stopOrder = queue.poll();

                long fulfilledOrderId = matchBidOrder(stopOrder.getOrderId(), stopOrder.getType(),
                        stopOrder.getSize(),
                        stopOrder.getUserId());
                if (fulfilledOrderId != -1) {
                    // stop order has been executed with no errors
                    stopOrder.setTimestamp(System.currentTimeMillis() / 1000L);

                    // notify the client that the order has been executed
                    UdpClientNotifier.notifyClient(stopOrder.getUserId(), stopOrder);

                    orderHistory.offer(stopOrder);
                }
            }
            stopBidOrders.remove(price); // Remove the stop order
        });

    }

    /*
     * Activate sell stop orders when the price changes
     */
    private void activateSellStopOrders(long currentPrice) {
        // Activate stop-sell
        stopAskOrders.tailMap(currentPrice, false).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                StopOrder stopOrder = queue.poll();
                long fulfilledOrderId = matchAskOrder(stopOrder.getOrderId(), stopOrder.getType(),
                        stopOrder.getSize(),
                        stopOrder.getUserId());
                if (fulfilledOrderId != -1) {
                    // stop order has been executed correttamente
                    stopOrder.setTimestamp(System.currentTimeMillis() / 1000L);
                    // notify the client that the order has been executed
                    UdpClientNotifier.notifyClient(stopOrder.getUserId(), stopOrder);
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

    /*
     * Insert a market order in the order book in order to execute it and fulfill
     * the request
     * return the order ID or -1 if the order was not executed
     */
    public long insertMarketOrder(long orderId, String type, long size, String userId) {
        long price = 0;
        if (type.equals("bid"))
            price = matchBidOrder(orderId, type, size, userId);
        else
            price = matchAskOrder(orderId, type, size, userId);

        // if the price is -1 it means that the order was not executed and it returns -1
        // as an error
        if (price == -1)
            return price;

        // finally create a new market order in order to add it to the order history
        MarketOrder markerOrder = new MarketOrder(orderId, type, size, price);
        markerOrder.setUserId(userId);

        // notify the client that the order has been executed
        UdpClientNotifier.notifyClient(userId, markerOrder);

        return addOrderHistory(markerOrder); // returns the order ID

    }

    /*
     * Execute a buy market order against the sell limit book
     * return the price of the order or -1 if the order was not executed
     */
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
        long fulfilledLimitOrderPrice = checkMatchLimitOrder.getPrice(); // get the price of the fulfilled limit order

        // set the timestamp of the executed order to the current time to notify the
        // order has been executed
        checkMatchLimitOrder.setTimestamp(System.currentTimeMillis() / 1000L);

        // notify the client that the order has been executed
        UdpClientNotifier.notifyClient(userId, checkMatchLimitOrder);
        // add the limit order that has been fulfilled to the order history
        addOrderHistory(checkMatchLimitOrder);

        try {
            long nextPrice = limitAskOrders.firstKey(); // get the next price in the sell limit book to activate the
                                                        // stop orders with the new lower sell price

            // Activate buy stop orders when the price changes
            activateBuyStopOrders(nextPrice);

        } catch (NoSuchElementException e) {
            // if the sell limit book is empty it means that the order has been executed
            // and there are no more orders to fulfill
        }

        return fulfilledLimitOrderPrice;
    }

    // Add an order to the order history and return the order ID
    public long addOrderHistory(Order order) {
        orderHistory.offer(order);
        return order.getOrderId();
    }

    // Execute a sell order against the buy book
    /*
     * Execute a sell market order against the buy limit book
     * return the price at which the market order has been closed (that it is equal
     * to price of the fulfilled limit order in the order book) or -1 if the order
     * was not executed
     */
    // TODO : to test
    public long matchAskOrder(long orderId, String type, long size, String userId) {

        LimitOrder checkMatchLimitOrder = limitBidOrders.get(limitBidOrders.firstKey()).peek();

        // if the buy limit book is empty or the first order is not enough to fulfill
        // the market order it returns -1 (error)
        if (checkMatchLimitOrder == null || checkMatchLimitOrder.getSize() < size) {
            return -1;
        }
        limitBidOrders.pollFirstEntry(); // remove the first entry of the buy limit book
        long fulfilledLimitOrderPrice = checkMatchLimitOrder.getPrice(); // get the price of the fulfilled limit order

        // set the timestamp of the executed order to the current time to notify the
        // order has been executed
        checkMatchLimitOrder.setTimestamp(System.currentTimeMillis() / 1000L);

        // notify the client that the order has been executed
        UdpClientNotifier.notifyClient(userId, checkMatchLimitOrder);

        addOrderHistory(checkMatchLimitOrder); // add the limit order to the order history
        try {

            long nextPrice = limitBidOrders.firstKey(); // get the next price in the buy limit book to activate the stop
                                                        // orders with the new higher buy price

            // Activate sell stop orders when the price changes
            activateSellStopOrders(nextPrice);

        } catch (NoSuchElementException e) {
            // if the buy limit book is empty it means that the order has been executed
            // and there are no more orders to fulfill
        }

        return fulfilledLimitOrderPrice;
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

        synchronized (book) {
            book.values().forEach(queue -> {
                if (queue.removeIf(order -> order.getOrderId() == orderId)) {
                    removed.set(true);
                }
            });
        }

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

        synchronized (book) {
            for (ConcurrentLinkedQueue<? extends Order> queue : book.values()) {
                for (Order order : queue) {
                    if (order.getOrderId() == orderId) {
                        return order;
                    }
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
        ConcurrentSkipListMap<String, TradeHistory> response = new ConcurrentSkipListMap<>();
        synchronized (ordersPerDay) {

            for (Map.Entry<Long, List<Order>> order : ordersPerDay.entrySet()) {
                // String date = LocalDate.ofEpochDay(order.getKey()).toString();
                long openingPrice = order.getValue().get(0).getPrice();
                long closingPrice = order.getValue().get(order.getValue().size() - 1).getPrice();
                long highPrice = order.getValue().stream().mapToLong(Order::getPrice).max().getAsLong();
                long lowPrice = order.getValue().stream().mapToLong(Order::getPrice).min().getAsLong();
                int dayOfMonth = LocalDate.ofEpochDay(order.getKey()).getDayOfMonth();

                TradeHistory tradeHistory = new TradeHistory(dayOfMonth, openingPrice, closingPrice, highPrice,
                        lowPrice, order.getValue());
                response.put(Integer.toString(dayOfMonth), tradeHistory);
            }
        }

        return response;

    }

    public void setLimitAskOrders(ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitAskOrders) {
        this.limitAskOrders = limitAskOrders;
    }

    public void setLimitBidOrders(ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitBidOrders) {
        this.limitBidOrders = limitBidOrders;
    }

    public void setStopBidOrders(ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopBidOrders) {
        this.stopBidOrders = stopBidOrders;
    }

    public void setStopAskOrders(ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopAskOrders) {
        this.stopAskOrders = stopAskOrders;
    }

    public void setOrderHistory(ConcurrentLinkedQueue<Order> orderHistory) {
        this.orderHistory = orderHistory;
    }

    public ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> getLimitAskOrders() {
        return limitAskOrders;
    }

    public ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> getLimitBidOrders() {
        return limitBidOrders;
    }

    public ConcurrentLinkedQueue<Order> getOrderHistory() {
        return orderHistory;
    }

    public ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> getStopBidOrders() {
        return stopBidOrders;
    }

    public ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> getStopAskOrders() {
        return stopAskOrders;
    }

}