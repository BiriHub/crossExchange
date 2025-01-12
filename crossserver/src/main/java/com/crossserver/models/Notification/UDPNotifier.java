package com.crossserver.models.Notification;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import com.crossserver.models.Orders.Order;
import com.google.gson.Gson;

public class UDPNotifier {
    private ConcurrentHashMap<String, InetSocketAddress> clientUdpAddresses;

    public UDPNotifier() {
        this.clientUdpAddresses = new ConcurrentHashMap<>();
    }

    public void unregisterUdpClient(String clientId) {
        clientUdpAddresses.remove(clientId);
    }

    public void registerUdpClient(String clientId, InetAddress host, int port) {
        InetSocketAddress udpAddress = new InetSocketAddress(host, port);
        clientUdpAddresses.put(clientId, udpAddress);
    }

    public void notifyClient(String clientId, Order order) {
        InetSocketAddress udpClientAddress = clientUdpAddresses.get(clientId);
        Gson gson = new Gson();
        if (udpClientAddress != null) {
            // send fulfilled order notification to client
            try (DatagramSocket socket = new DatagramSocket()) {

                // Create JSON notification message
                HashMap<String, Object> message = new HashMap<>();
                message.put("notification", "closedTrades");

                // Create JSON array of trades that have been closed in the same moment
                ArrayList<Map<String, Object>> trades = new ArrayList<>();
                Map<String, Object> trade = new HashMap<>();
                trade.put("orderId", order.getOrderId());
                trade.put("type", order.getType());
                trade.put("orderType", order.getOrderType());
                trade.put("size", order.getSize());
                trade.put("price", order.getPrice());
                trade.put("timestamp", order.getTimestamp());
                trades.add(trade);

                message.put("trades", trades);

                // Create JSON string
                String jsonMessage = gson.toJson(message);

                // Send JSON notification message
                byte[] data = jsonMessage.getBytes(StandardCharsets.UTF_8);

                DatagramPacket packet = new DatagramPacket(data, data.length, udpClientAddress);
                socket.send(packet);
            } catch (IOException e) {
                System.err.println("Error sending notification to " + clientId + ": " + e.getMessage());
            }
        }
    }

}
