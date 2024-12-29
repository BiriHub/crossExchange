package com.crossserver.models.orders;

import java.util.Comparator;
import java.util.concurrent.*;

public class OrderBook {
    private final ConcurrentSkipListMap<Integer, ConcurrentLinkedQueue<Order>> askBook; // Vendita
    private final ConcurrentSkipListMap<Integer, ConcurrentLinkedQueue<Order>> bidBook; // Acquisto

    public OrderBook() {
        this.askBook = new ConcurrentSkipListMap<>();
        this.bidBook = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
    }

    // Aggiunge un ordine di vendita
    public void addAskOrder(Order order) {
        askBook.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
    }

    // Aggiunge un ordine di acquisto
    public void addBidOrder(Order order) {
        bidBook.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).offer(order);
    }

    // Esegue un ordine di acquisto contro il book di vendita
    public void matchBidOrder(Order order) {
        int remainingSize = order.getSize();
        for (var entry : askBook.entrySet()) {
            int price = entry.getKey();
            ConcurrentLinkedQueue<Order> queue = entry.getValue();

            while (!queue.isEmpty() && remainingSize > 0) {
                Order askOrder = queue.poll();
                int tradeSize = Math.min(askOrder.getSize(), remainingSize);

                // Simula il matching
                askOrder.reduceSize(tradeSize);
                remainingSize -= tradeSize;

                if (askOrder.getSize() > 0) {
                    queue.offer(askOrder); // Rimetti l'ordine parzialmente eseguito
                }

                if (remainingSize == 0) break;
            }

            if (remainingSize == 0) break;
        }
    }

    // Esegue un ordine di vendita contro il book di acquisto
    public void matchAskOrder(Order order) {
        int remainingSize = order.getSize();
        for (var entry : bidBook.entrySet()) {
            int price = entry.getKey();
            ConcurrentLinkedQueue<Order> queue = entry.getValue();

            while (!queue.isEmpty() && remainingSize > 0) {
                Order bidOrder = queue.poll();
                int tradeSize = Math.min(bidOrder.getSize(), remainingSize);

                // Simula il matching
                bidOrder.reduceSize(tradeSize);
                remainingSize -= tradeSize;

                if (bidOrder.getSize() > 0) {
                    queue.offer(bidOrder); // Rimetti l'ordine parzialmente eseguito
                }

                if (remainingSize == 0) break;
            }

            if (remainingSize == 0) break;
        }
    }
}
