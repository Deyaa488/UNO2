package com.uno3d.lan;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class UnoWebSocketServer extends WebSocketServer {
    private final Map<Integer, WebSocket> clientMap = Collections.synchronizedMap(new HashMap<>());
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
        clientMap.put(conn.hashCode(), conn);
        if (listener != null) listener.onClientConnected(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clientMap.remove(conn.hashCode());
        if (listener != null) listener.onClientDisconnected(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (listener != null) listener.onMessageReceived(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {}

    @Override
    public void onStart() {}

    public void broadcastMessage(String message) {
        synchronized (clientMap) {
            for (WebSocket client : clientMap.values()) {
                if (client.isOpen()) {
                    client.send(message);
                }
            }
        }
    }

    public void sendToClient(int clientId, String message) {
        WebSocket conn = clientMap.get(clientId);
        if (conn != null && conn.isOpen()) {
            conn.send(message);
        }
    }
}
