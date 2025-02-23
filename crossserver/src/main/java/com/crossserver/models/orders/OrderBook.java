package com.crossserver.models.Orders;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import com.crossserver.models.Notification.UDPNotifier;

/*
 * This class is responsible for managing the order book, inserting, executing and canceling orders
 */
public class OrderBook {
    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitAskOrders; // map of limit sell orders
    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitBidOrders; // map of limit buy orders

    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopBidOrders; // map of stop buy orders
    private ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopAskOrders; // map of stop sell orders

    private ConcurrentLinkedQueue<Order> orderHistory; // list of executed orders

    private final UDPNotifier UdpClientNotifier; // reference to the UDP notifier

    public OrderBook(UDPNotifier UdpClientNotifier) {
        this.UdpClientNotifier = UdpClientNotifier;
        this.limitAskOrders = new ConcurrentSkipListMap<>();
        this.limitBidOrders = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.orderHistory = new ConcurrentLinkedQueue<>();
        this.stopBidOrders = new ConcurrentSkipListMap<>();
        this.stopAskOrders = new ConcurrentSkipListMap<>();
    }

    /*
     * Insert a stop order in the order book according to its type (bid or ask) and
     * return the order ID
     */
    public long insertStopOrder(StopOrder order) {
        if (order.getType().equals("bid")) {
            stopBidOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        } else if (order.getType().equals("ask")) {
            stopAskOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        }
        return order.getOrderId();
    }

    /*
     * Activate buy stop orders when the price changes in the limit ask book
     */
    private void activateBuyStopOrders(long currentPrice) {
        // Activate stop-buy
        synchronized (stopBidOrders) {
            stopBidOrders.headMap(currentPrice, true).forEach((price, queue) -> {
                while (!queue.isEmpty()) {
                    StopOrder stopOrder = queue.poll();

                    long fulfilledOrderId = matchBidOrder(stopOrder.getOrderId(), stopOrder.getType(),
                            stopOrder.getSize(), stopOrder.getUserId());
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
    }

    /*
     * Activate sell stop orders when the price changes in the limit bid book
     */
    private void activateSellStopOrders(long currentPrice) {

        synchronized (stopAskOrders) {
            // Activate stop-sell
            stopAskOrders.headMap(currentPrice, true).forEach((price, queue) -> {
                while (!queue.isEmpty()) {
                    StopOrder stopOrder = queue.poll();

                    long fulfilledOrderId = matchAskOrder(stopOrder.getOrderId(), stopOrder.getType(),
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
                stopAskOrders.remove(price); // Remove the stop order
            });
        }
    }

    /*
     * Insert a limit order in the order book according to its type (bid or ask) and
     * return the order ID
     */
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
     * the request, return the order ID or -1 if the order was not executed
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
        markerOrder.setTimestamp(System.currentTimeMillis() / 1000L);

        // notify the client that the order has been executed
        UdpClientNotifier.notifyClient(userId, markerOrder);

        return addOrderHistory(markerOrder); // returns the order ID

    }

    /*
     * Execute a buy market order against the sell limit book at the lowest price
     * return the
     * price of order or -1 if the order was not possible to execute
     */
    public long matchBidOrder(long orderId, String type, long size, String userId) {

        // the ask limit book is empty, the order can not be executed
        if (limitAskOrders.entrySet().isEmpty()) {
            return -1;
        }
        // extract the list of limit order with the lowest price in the sell limit book
        ConcurrentLinkedQueue<LimitOrder> lowestPriceLimitOrders = limitAskOrders.get(limitAskOrders.firstKey());

        // the market order can not be executed if the sell limit book is empty
        if (lowestPriceLimitOrders == null) {
            limitAskOrders.pollFirstEntry(); // remove the first entry of the sell limit book
            return -1;
        }

        long totalSize = 0;

        synchronized (lowestPriceLimitOrders) {
            // calculate the total size of the limit orders with the lowest price
            totalSize = lowestPriceLimitOrders.stream().mapToLong(LimitOrder::getSize).sum();
        }

        /*
         * the market order can not be executed at the lowest price
         * if the total size of the limit orders is less the its size
         */
        if (totalSize < size) {
            return -1;
        }

        long remainingMarketOrderSize = size;

        // get the price of lowest limit order in the sell limit book
        long fulfilledLimitOrderPrice = limitAskOrders.firstKey();

        while (remainingMarketOrderSize > 0) {

            // extract the oldest limit order with the lowest price
            LimitOrder checkMatchLimitOrder = lowestPriceLimitOrders.peek();

            if (checkMatchLimitOrder.getSize() <= remainingMarketOrderSize) {

                remainingMarketOrderSize -= checkMatchLimitOrder.getSize();
                totalSize -= checkMatchLimitOrder.getSize();

                // remove the fulfilled limit order from the sell limit book
                lowestPriceLimitOrders.poll();

                // set the timestamp of the executed order to the current time to notify the
                // order has
                // been executed
                checkMatchLimitOrder.setTimestamp(System.currentTimeMillis() / 1000L);

                // notify the owner of the fulfilled limit order that it has been executed
                UdpClientNotifier.notifyClient(checkMatchLimitOrder.getUserId(), checkMatchLimitOrder);

                // add the limit order that has been fulfilled to the order history
                addOrderHistory(checkMatchLimitOrder);

                /*
                 * if the total size of the limit orders with the lowest price is 0 it means
                 * that the order list has been emptied thanks to the market order
                 * and now it is necessary to activate the stop orders linked to the new lower
                 * sell price
                 */
                if (totalSize == 0) {
                    /*
                     * remove the previous entry linked to the lowest price in the sell limit book
                     * that now it has been emptied
                     */
                    limitAskOrders.pollFirstEntry();

                    try {
                        /*
                         * get the next price in the sell limit book to activate the stop orders with
                         * the
                         * new lower sell price
                         */
                        long nextPrice = limitAskOrders.firstKey();

                        // Activate buy stop orders when the price changes
                        activateBuyStopOrders(nextPrice);

                    } catch (NoSuchElementException e) {
                        // if the sell limit book is empty it means that the order has been executed
                        // and there are no more orders to fulfill
                    }
                }
            } else {
                checkMatchLimitOrder.setSize(checkMatchLimitOrder.getSize() - remainingMarketOrderSize);
                remainingMarketOrderSize = 0;
            }
        }
        return fulfilledLimitOrderPrice;
    }

    // Add an order to the order history and return the order ID
    public long addOrderHistory(Order order) {
        orderHistory.offer(order);
        return order.getOrderId();
    }

    /*
     * Execute a sell market order against the buy limit book return the price at
     * which the
     * market order has been closed (that it is equal to price of the fulfilled
     * limit order in
     * the order book) or -1 if the order was not executed
     */
    public long matchAskOrder(long orderId, String type, long size, String userId) {

        // the buy limit book is empty, the order can not be executed
        if (limitBidOrders.entrySet().isEmpty()) {
            return -1;
        }

        // extract the list of limit order with the highest price in the buy limit book
        ConcurrentLinkedQueue<LimitOrder> highestPriceLimitOrders = limitBidOrders.get(limitBidOrders.firstKey());

        // the market order can not be executed if the buy limit book is empty
        if (highestPriceLimitOrders == null) {
            limitBidOrders.pollFirstEntry(); // remove the first entry of the buy limit book
            return -1;
        }

        long totalSize = 0;

        synchronized (highestPriceLimitOrders) {
            // calculate the total size of the limit orders with the highest price
            totalSize = highestPriceLimitOrders.stream().mapToLong(LimitOrder::getSize).sum();
        }

        /*
         * the market order can not be executed at the highest price if the total size
         * of
         * the limit orders is less the its size
         */
        if (totalSize < size) {
            return -1;
        }

        long remainingMarketOrderSize = size;

        // get the price of highest limit order in the buy limit book
        long fulfilledLimitOrderPrice = limitBidOrders.firstKey();

        while (remainingMarketOrderSize > 0) {

            // extract the oldest limit order with the highest price
            LimitOrder checkMatchLimitOrder = highestPriceLimitOrders.peek();

            if (checkMatchLimitOrder.getSize() <= remainingMarketOrderSize) {

                remainingMarketOrderSize -= checkMatchLimitOrder.getSize();
                totalSize -= checkMatchLimitOrder.getSize();

                // remove the fulfilled limit order from the buy limit book
                highestPriceLimitOrders.poll();

                /*
                 * set the timestamp of the executed order to the current time to notify the
                 * order has been executed
                 */
                checkMatchLimitOrder.setTimestamp(System.currentTimeMillis() / 1000L);

                // notify the owner of the fulfilled limit order that it has been executed
                UdpClientNotifier.notifyClient(checkMatchLimitOrder.getUserId(), checkMatchLimitOrder);

                // add the limit order that has been fulfilled to the order history
                addOrderHistory(checkMatchLimitOrder);

                /*
                 * if the total size of the limit orders with the highest price is 0 it means
                 * that the order list has been emptied thanks to the market order and now it is
                 * necessary to activate the stop orders linked to the new higher buy price
                 */
                if (totalSize == 0) {

                    /*
                     * remove the previous entry linked to the highest price in the buy limit book
                     * that now it has been emptied
                     */
                    limitBidOrders.pollFirstEntry();

                    try {
                        /*
                         * get the next price in the buy limit book to activate the stop orders with the
                         * new higher buy price
                         */
                        long nextPrice = limitBidOrders.firstKey();

                        // Activate sell stop orders when the price changes
                        activateSellStopOrders(nextPrice);

                    } catch (NoSuchElementException e) {
                        // if the buy limit book is empty it means that the order has been executed
                        // and there are no more orders to fulfill
                    }
                }
            } else {
                checkMatchLimitOrder.setSize(checkMatchLimitOrder.getSize() - remainingMarketOrderSize);
                remainingMarketOrderSize = 0;
            }
        }

        return fulfilledLimitOrderPrice;
    }

    /*
     * Cancel an order from the order book given its ID, return the order ID if the
     * order is present in one of the data structures, -1 otherwise
     */
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

    /*
     * Search for the order specified by its ID in the data structure passed as
     * parameter, if found then it is removed and return true, false otherwise
     */
    private boolean cancelOrderFromBook(
            ConcurrentSkipListMap<Long, ? extends ConcurrentLinkedQueue<? extends Order>> book, long orderId) {
        AtomicBoolean removed = new AtomicBoolean(false);

        synchronized (book) {
            book.entrySet().forEach(entry -> {
                ConcurrentLinkedQueue<? extends Order> queue = entry.getValue();
                if (queue.removeIf(order -> order.getOrderId() == orderId)) {
                    removed.set(true);
                    // if removing the oerder from the queue makes it empty, remove the entry from
                    // the book
                    if (queue.isEmpty()) {
                        book.remove(entry.getKey());
                    }
                }
            });
        }

        return removed.get();
    }

    /*
     * Search the order in the order book from its ID and return it if found, null
     * otherwise
     */
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

        order = findOrderInBook(stopBidOrders, orderId);
        if (order != null)
            return order;

        /*
         * create a copy of the order history instead of directly accessing it to avoid
         * costful synchronization
         */
        ConcurrentLinkedQueue<Order> copyOrderHistory = new ConcurrentLinkedQueue<>();

        synchronized (orderHistory) {
            copyOrderHistory.addAll(orderHistory);
        }

        // search for the order in the copy of the order history
        for (Order orderInHistory : copyOrderHistory) {
            if (orderInHistory.getOrderId() == orderId) {
                return orderInHistory;
            }
        }
        return null;
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

    /*
     * Return the order history of the month specified by the start and end of the
     * month in a map that associate
     * the day of the month with the trade history of that day
     */
    public ConcurrentSkipListMap<String, TradeHistory> getOrderHistory(long startOfMonth, long endOfMonth) {
        List<Order> orderHistoryByMonth;
        Map<Long, List<Order>> ordersPerDay;
        synchronized (orderHistory) {
            /*
             * Filter the order history by the month specified by the start and end of the
             * month timestamps then sort and group the orders by timestamp
             */
            orderHistoryByMonth = orderHistory.stream().filter(order -> {
                long orderDateTimestamp = order.getTimestamp();
                return orderDateTimestamp >= startOfMonth && orderDateTimestamp <= endOfMonth;
            }).sorted(Comparator.comparing(ord -> ord.getTimestamp())).collect(Collectors.toList());
            ordersPerDay = orderHistoryByMonth.stream().collect(Collectors.groupingBy(Order::getTimestamp));
        }

        // Create the map of orders per day sorted by number of day
        SortedMap<Long, List<Order>> ordersPerDaySorted = new TreeMap<>(ordersPerDay);

        ConcurrentSkipListMap<String, TradeHistory> response = new ConcurrentSkipListMap<>();

        // Iterate over the orders per day sorted by day
        for (Map.Entry<Long, List<Order>> order : ordersPerDaySorted.entrySet()) {

            // Sort the orders by timestamp
            List<Order> orders = order.getValue().stream().sorted(Comparator.comparing(Order::getTimestamp))
                    .collect(Collectors.toList());

            // Extract the opening, closing and search for the high and low price of the day
            long openingPrice = orders.get(0).getPrice();
            long closingPrice = orders.get(orders.size() - 1).getPrice();
            long highPrice = orders.stream().mapToLong(Order::getPrice).max().getAsLong();
            long lowPrice = orders.stream().mapToLong(Order::getPrice).min().getAsLong();

            // Compute the day of the month
            int dayOfMonth = LocalDate.ofEpochDay(order.getKey()).getDayOfMonth();

            TradeHistory tradeHistory = new TradeHistory(dayOfMonth, openingPrice, closingPrice, highPrice,
                    lowPrice, orders);
            response.put(Integer.toString(dayOfMonth), tradeHistory);
        }

        return response;

    }

    public synchronized void setLimitAskOrders(
            ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitAskOrders) {
        this.limitAskOrders = new ConcurrentSkipListMap<>(limitAskOrders);
    }

    public synchronized void setLimitBidOrders(
            ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> limitBidOrders) {
        this.limitBidOrders = new ConcurrentSkipListMap<>(limitBidOrders);
    }

    public synchronized void setStopBidOrders(
            ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopBidOrders) {
        this.stopBidOrders = new ConcurrentSkipListMap<>(stopBidOrders);
    }

    public synchronized void setStopAskOrders(
            ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<StopOrder>> stopAskOrders) {
        this.stopAskOrders = new ConcurrentSkipListMap<>(stopAskOrders);
    }

    public synchronized void setOrderHistory(ConcurrentLinkedQueue<Order> orderHistory) {
        this.orderHistory = new ConcurrentLinkedQueue<>(orderHistory);
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