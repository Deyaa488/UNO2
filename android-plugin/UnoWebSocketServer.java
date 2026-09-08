package com.uno3d.lan;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class UnoWebSocketServer extends WebSocketServer {
    private final Set<WebSocket> clients = Collections.synchronizedSet(new HashSet<>());
    private MessageListener listener;

    public interface MessageListener {
        void onMessageReceived(WebSocket conn, String message);
        void onClientConnected(WebSocket conn);
        void onClientDisconnected(WebSocket conn);
    }

    public UnoWebSocketServer(int port, MessageListener listener) {
        super(new InetSocketAddress(port));
        this.listener = listener;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        if (listener != null) listener.onClientConnected(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        if (listener != null) listener.onClientDisconnected(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // Milestone 1 Prototype test handler: reply to test_ping with test_pong
        if (message.contains(""type":"test_ping"")) {
            conn.send("{"type":"test_pong","timestamp":" + System.currentTimeMillis() + ","server":"Android-Native-WebSocket"}");
            return;
        }
        if (listener != null) listener.onMessageReceived(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {}

    @Override
    public void onStart() {}

    public void broadcastMessage(String message) {
        synchronized (clients) {
            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    client.send(message);
                }
            }
        }
    }
}
