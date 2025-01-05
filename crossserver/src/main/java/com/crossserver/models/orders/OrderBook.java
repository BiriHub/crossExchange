package com.crossserver.models.orders;

import java.util.Comparator;
import java.util.Map.Entry;
import java.util.concurrent.*;

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
                    stopOrder.setTimestamp(System.currentTimeMillis());
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
                    stopOrder.setTimestamp(System.currentTimeMillis());
                    orderHistory.offer(stopOrder);
                }

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
    public long insertMarketOrder(long orderId, String type, long size, String userId) {
        if (type.equals("bid"))
            return matchBidOrder(orderId, type, size, userId);
        else
            return matchAskOrder(orderId, type, size, userId);
    }

    // Esegue un ordine di acquisto contro il book di vendita
    // TODO : to test
    public long matchBidOrder(long orderId, String type, long size, String userId) {
        long remainingSize = size;
        long executedOrderPrice = 0; // price of the first executed order, it will be the price of the market order
        boolean assigned = false; // flag to check if the price has been assigned to the order

        // Copy the ask book in order to save the original state of the book in case of
        // impossibility to fulfill the order
        ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> temp = new ConcurrentSkipListMap<>(
                limitAskOrders);

        for (Entry<Long, ConcurrentLinkedQueue<LimitOrder>> entry : temp.entrySet()) {
            long price = entry.getKey();
            ConcurrentLinkedQueue<LimitOrder> queue = entry.getValue();

            if (!assigned) {
                executedOrderPrice = price;
                assigned = true;
            }

            while (!queue.isEmpty() && remainingSize > 0) {
                LimitOrder askOrder = queue.peek(); // TODO peek or poll?
                long tradeSize = Math.min(askOrder.getSize(), remainingSize);

                // Reduce the size of the ask order
                remainingSize -= tradeSize;

                // Simulate the matching
                askOrder.setSize(askOrder.getSize() - tradeSize); // Reduce the size of the ask order

                if (askOrder.getSize() == 0) {
                    queue.poll(); // extract the executed order back in the queue
                    askOrder.setTimestamp(System.currentTimeMillis()); // set the moment when the limit order has been
                                                                       // executed
                    // TODO: add the users notification automatic sending operation for the limit
                    // orders
                    addOrderHistory(askOrder); // add the limit order to the order history
                }
                // Activate stop orders when the price changes
                activateStopOrders(price);
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
        for (Entry<Long, ConcurrentLinkedQueue<LimitOrder>> entry : temp.entrySet()) {
            limitAskOrders.put(entry.getKey(), entry.getValue());
        }

        MarketOrder markerOrder = new MarketOrder(orderId, type, size, executedOrderPrice);
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
        long remainingSize = size;
        long executedOrderPrice = 0;
        boolean assigned = false; // flag to check if the price has been assigned to the order

        // Copy the bid book in order to save the original state of the book in case of
        // impossibility to fulfill the order
        ConcurrentSkipListMap<Long, ConcurrentLinkedQueue<LimitOrder>> temp = new ConcurrentSkipListMap<>(
                limitBidOrders);

        for (Entry<Long, ConcurrentLinkedQueue<LimitOrder>> entry : temp.entrySet()) {
            long price = entry.getKey();
            ConcurrentLinkedQueue<LimitOrder> queue = entry.getValue();

            while (!queue.isEmpty() && remainingSize > 0) {
                Order bidOrder = queue.peek(); // TODO peek or poll?
                long tradeSize = Math.min(bidOrder.getSize(), remainingSize);

                // Reduce the size of the bid order
                remainingSize -= tradeSize;

                // simulate the matching
                bidOrder.setSize(bidOrder.getSize() - tradeSize); // Reduce the size of the bid order

                if (bidOrder.getSize() == 0) {
                    queue.poll(); // extract the executed order back in the queue
                    bidOrder.setTimestamp(System.currentTimeMillis()); // set the moment when the limit order has been
                                                                       // executed
                    // TODO: add the users notification automatic sending operation for the limit
                    // orders
                    addOrderHistory(bidOrder); // add the limit order to the order history
                }
                // Assign the price of the executed order only once
                activateStopOrders(price);

            }
            if (remainingSize == 0)
                break;

            if (!assigned) {
                executedOrderPrice = price;
                assigned = true;
            }

        }

        if (remainingSize > 0) {
            return -1; // Error, the order cannot be fulfilled
        }

        // Apply changes from temp to limitBidOrders
        for (Entry<Long, ConcurrentLinkedQueue<LimitOrder>> entry : temp.entrySet()) {
            limitBidOrders.put(entry.getKey(), entry.getValue());
        }

        MarketOrder markerOrder = new MarketOrder(orderId, type, size, executedOrderPrice);
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

        for (ConcurrentLinkedQueue<? extends Order> queue : book.values()) {
            if (queue.removeIf(order -> order.getOrderId() == orderId)) {
                return true;
            }
        }
        return false;
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
 // Find an order in the specified data structure given its ID , return the order if found, null otherwise
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
}