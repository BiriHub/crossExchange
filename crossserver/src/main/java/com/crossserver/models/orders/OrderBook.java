package com.crossserver.models.orders;

import java.util.Comparator;
import java.util.Map.Entry;
import java.util.concurrent.*;

public class OrderBook {
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> limitAskOrders; // sell
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> limitBidOrders; // buy
    private final ConcurrentLinkedQueue<Order> orderHistory; // order history

    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> stopBidOrders;
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> stopAskOrders;

    public OrderBook() {
        this.limitAskOrders = new ConcurrentSkipListMap<>();
        this.limitBidOrders = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.orderHistory = new ConcurrentLinkedQueue<>();
        this.stopBidOrders = new ConcurrentSkipListMap<>();
        this.stopAskOrders = new ConcurrentSkipListMap<>();
    }

    public void insertStopOrder(Order order) {
        if (order.getType().equals("bid")) {
            stopBidOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        } else if (order.getType().equals("ask")) {
            stopAskOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        }
    }

    private void activateStopOrders(long currentPrice) {
        // Activate stop-buy
        stopBidOrders.headMap(currentPrice, true).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                Order stopOrder = queue.poll();
                MarketOrder marketOrder = new MarketOrder(stopOrder.getOrderId(), stopOrder.getType(),
                        stopOrder.getSize(),
                        currentPrice);

                insertMarketOrder(marketOrder.getOrderId(), marketOrder.getType(), marketOrder.getSize(),stopOrder.getUserId());
            }
            stopBidOrders.remove(price); // Remove the stop order
        });

        // Activate stop-sell
        stopAskOrders.tailMap(currentPrice, false).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                Order stopOrder = queue.poll();
                insertLimitOrder(stopOrder); // Create a limit order
            }
            stopAskOrders.remove(price); // Remove the stop order
        });
    }

    // Insert a limit order in the order book according to its type (bid or ask),
    // return the order ID
    public long insertLimitOrder(Order order) {
        if (order.getType().equals("bid"))
            // Add a new bid order to the limit order
            limitBidOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        else
            // Add an ask order to the limit order book
            limitAskOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);

        return order.getOrderId();
    }

    // Execute a market order
    public long insertMarketOrder(long orderId, String type, long size, long userId) {
        if (type.equals("bid"))
            return matchBidOrder(orderId, type, size, userId);
        else
            return matchAskOrder(orderId, type, size, userId);
    }

    // Esegue un ordine di acquisto contro il book di vendita
    // TODO : to test
    public long matchBidOrder(long orderId, String type, long size, long userId) {
        long remainingSize = size;
        long executedOrderPrice = 0; // price of the first executed order, it will be the price of the market order
        boolean assigned = false; // flag to check if the price has been assigned to the order

        // Copy the ask book in order to save the original state of the book in case of
        // impossibility to fulfill the order
        ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> temp = new ConcurrentSkipListMap<>(limitAskOrders);

        for (Entry<Long, ConcurrentLinkedQueue<Order>> entry : temp.entrySet()) {
            long price = entry.getKey();
            ConcurrentLinkedQueue<Order> queue = entry.getValue();

            while (!queue.isEmpty() && remainingSize > 0) {
                Order askOrder = queue.peek(); // TODO peek or poll?
                long tradeSize = Math.min(askOrder.getSize(), remainingSize);

                // Reduce the size of the ask order
                remainingSize -= tradeSize;

                // Simulate the matching
                askOrder.setSize(askOrder.getSize() - tradeSize); // Reduce the size of the ask order

                if (askOrder.getSize() == 0) {
                    queue.poll(); // extract the executed order back in the queue
                    // TODO: add the users notification automatic sending operation for the limit
                    // orders
                }
                // Activate stop orders when the price changes
                activateStopOrders(price);
            }

            // Assign the price of the executed order only once
            // TODO: temporary solution, the price of the last executed order is assigned to
            // the market order
            if (!assigned) {
                executedOrderPrice = price;
                assigned = true;
            }

            if (remainingSize == 0)
                break;

        }

        if (remainingSize > 0) {
            return -1; // Error, the order cannot be fulfilled
        }

        // TODO RIPRENDI DA QUA PER IL CALCOLO DEL PREZZO MEDIO FINALE DI ESECUZIONE
        // DELL'MARKET ORDER

        // Apply changes from temp to limitAskOrders
        for (Entry<Long, ConcurrentLinkedQueue<Order>> entry : temp.entrySet()) {
            limitAskOrders.put(entry.getKey(), entry.getValue());
        }

        MarketOrder markerOrder = new MarketOrder(orderId, type, size, executedOrderPrice);
        // TODO: add the market order to the order history
        return addOrderHistory(markerOrder);
    }

    // Add an order to the order history and return the order ID
    public long addOrderHistory(Order order) {
        orderHistory.offer(order);
        return order.getOrderId();
    }

    // Execute a sell order against the buy book
    // TODO : to test
    public long matchAskOrder(String type, long size, long userId) {
        long remainingSize = size;
        long executedOrderPrice = 0;
        boolean assigned = false; // flag to check if the price has been assigned to the order

        // Copy the bid book in order to save the original state of the book in case of
        // impossibility to fulfill the order
        ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> temp = new ConcurrentSkipListMap<>(limitBidOrders);

        for (Entry<Long, ConcurrentLinkedQueue<Order>> entry : temp.entrySet()) {
            long price = entry.getKey();
            ConcurrentLinkedQueue<Order> queue = entry.getValue();

            while (!queue.isEmpty() && remainingSize > 0) {
                Order bidOrder = queue.peek(); // TODO peek or poll?
                long orderSize = bidOrder.getSize();
                long tradeSize = Math.min(orderSize, remainingSize);

                // Reduce the size of the bid order
                orderSize -= tradeSize;
                remainingSize -= tradeSize;

                // simulate the matching
                bidOrder.setSize(orderSize); // Reduce the size of the bid order

                if (orderSize > 0) {
                    queue.poll(); // extra the executed order back in the queue
                    // TODO: add the users notification automatic sending operation for the limit
                    // orders
                }
                // Assign the price of the executed order only once

            }
            if (remainingSize == 0)
                break;

            if (!assigned) {
                executedOrderPrice = price;
                assigned = true;
                activateStopOrders(executedOrderPrice); // Attivazione degli stop order
            }

        }

        if (remainingSize > 0) {
            return -1; // Error, the order cannot be fulfilled
        }

        // Apply changes from temp to limitBidOrders
        for (Entry<Long, ConcurrentLinkedQueue<Order>> entry : temp.entrySet()) {
            limitBidOrders.put(entry.getKey(), entry.getValue());
        }

        Order marketOrder = new Order(type, "market", size, executedOrderPrice, timestamp, userId);
        return addOrderHistory(marketOrder);
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
        return orderId;
    }

    // TODO : to test
    private boolean cancelOrderFromBook(ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> book, long orderId) {
        for (ConcurrentLinkedQueue<Order> queue : book.values()) {
            if (queue.removeIf(order -> order.getOrderId() == orderId)) {
                return true;
            }
        }
        return false;
    }

    public Order getOrder(long orderId) {
        Order order = findOrderInBook(limitAskOrders, orderId);
        if (order != null) return order;

        order = findOrderInBook(limitBidOrders, orderId);
        if (order != null) return order;

        order = findOrderInBook(stopAskOrders, orderId);
        if (order != null) return order;

        return findOrderInBook(stopBidOrders, orderId);
    }

    private Order findOrderInBook(ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> book, long orderId) {
        for (ConcurrentLinkedQueue<Order> queue : book.values()) {
            for (Order order : queue) {
                if (order.getOrderId() == orderId) {
                    return order;
                }
            }
        }
        return null;