package com.crossserver.models.orders;

import java.util.Comparator;
import java.util.Map.Entry;
import java.util.concurrent.*;

public class OrderBook {
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> askBook; // sell
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> bidBook; // buy
    private final ConcurrentLinkedQueue<Order> orderHistory; // order history

    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> stopBidOrders;
    private final ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> stopAskOrders;

    public OrderBook() {
        this.askBook = new ConcurrentSkipListMap<>();
        this.bidBook = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.orderHistory = new ConcurrentLinkedQueue<>();
        this.stopBidOrders = new ConcurrentSkipListMap<>();
        this.stopAskOrders = new ConcurrentSkipListMap<>();
    }

    // Add an order to the order book
    private void addAskOrder(Order order) {
        askBook.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
    }

    public void insertStopOrder(Order order) {
        if (order.getType().equals("buy")) {
            stopBidOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        } else if (order.getType().equals("sell")) {
            stopAskOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
        }
    }

    private void activateStopOrders(long currentPrice) {
        // Activate stop-buy
        stopBidOrders.headMap(currentPrice, true).forEach((price, queue) -> {
            while (!queue.isEmpty()) {
                Order stopOrder = queue.poll();
                insertLimitOrder(stopOrder); // Create a limit order
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

    public void insertLimitOrder(Order order) {
        if (order.getType().equals("bid"))
            addBidOrder(order);
        else
            addAskOrder(order);
    }

    // Aggiunge un ordine di acquisto
    private void addBidOrder(Order order) {
        bidBook.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
    }

    public long insertMarketOrder(String type, long size, long timestamp, long userId) {
        if (type.equals("bid"))
            return matchAskOrder(type, size, timestamp, userId);
        else
            return matchBidOrder(type, size, timestamp, userId);
    }

    // Esegue un ordine di acquisto contro il book di vendita
    // TODO : to test
    public long matchBidOrder(String type, long size, long timestamp, long userId) {
        long remainingSize = size;
        long executedOrderPrice = 0;
        boolean assigned = false; // flag to check if the price has been assigned to the order

        // Copy the ask book in order to save the original state of the book in case of
        // impossibility to fulfill the order
        ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> temp = new ConcurrentSkipListMap<>(askBook);

        for (Entry<Long, ConcurrentLinkedQueue<Order>> entry : temp.entrySet()) {
            long price = entry.getKey();
            ConcurrentLinkedQueue<Order> queue = entry.getValue();

            while (!queue.isEmpty() && remainingSize > 0) {
                Order askOrder = queue.peek(); // TODO peek or poll?
                long orderSize = askOrder.getSize();
                long tradeSize = Math.min(orderSize, remainingSize);

                // Reduce the size of the ask order
                orderSize -= tradeSize;
                remainingSize -= tradeSize;

                // Simulate the matching
                askOrder.setSize(orderSize); // Reduce the size of the ask order

                if (orderSize > 0) {
                    queue.poll(); // extra the executed order back in the queue
                    // TODO: add the users notification automatic sending operation for the limit
                    // orders
                }

            }
            if (remainingSize == 0)
                break;

            // Assign the price of the executed order only once
            // TODO: temporary solution, the price of the last executed order is assigned to
            // the market order
            if (!assigned) {
                executedOrderPrice = price;
                assigned = true;
                activateStopOrders(executedOrderPrice); // Attivazione degli stop order
            }

        }

        if (remainingSize > 0) {
            return -1; // Error, the order cannot be fulfilled
        }

        // TODO RIPRENDI DA QUA PER IL CALCOLO DEL PREZZO MEDIO FINALE DI ESECUZIONE
        // DELL'MARKET ORDER

        // Apply changes from temp to askBook
        for (var entry : temp.entrySet()) {
            askBook.put(entry.getKey(), entry.getValue());
        }
        Order markerOrder = new Order(type, "market", size, executedOrderPrice, timestamp, userId);
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
    public long matchAskOrder(String type, long size, long timestamp, long userId) {
        long remainingSize = size;
        long executedOrderPrice = 0;
        boolean assigned = false; // flag to check if the price has been assigned to the order

        // Copy the bid book in order to save the original state of the book in case of
        // impossibility to fulfill the order
        ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<Order>> temp = new ConcurrentSkipListMap<>(bidBook);

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

        // Apply changes from temp to bidBook
        for (Entry<Long, ConcurrentLinkedQueue<Order>> entry : temp.entrySet()) {
            bidBook.put(entry.getKey(), entry.getValue());
        }

        Order marketOrder = new Order(type, "market", size, executedOrderPrice, timestamp, userId);
        return addOrderHistory(marketOrder);
    }

    // Cancel an order from the order book
    public long cancelOrder(long orderId) {
        if (cancelOrderFromBook(askBook, orderId))
            return orderId;
        if (cancelOrderFromBook(bidBook, orderId))
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
        Order order = findOrderInBook(askBook, orderId);
        if (order != null) return order;

        order = findOrderInBook(bidBook, orderId);
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