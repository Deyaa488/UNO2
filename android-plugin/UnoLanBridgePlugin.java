package com.uno3d.lan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.view.WindowManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.java_websocket.WebSocket;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

@CapacitorPlugin(name = "UnoLanBridge")
public class UnoLanBridgePlugin extends Plugin {
    private static final String SERVICE_TYPE = "_uno3d._tcp.";
    private UnoWebSocketServer server;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void load() {
        super.load();
        nsdManager = (NsdManager) getContext().getSystemService(Context.NSD_SERVICE);
    }

    @PluginMethod
    public void startHostServer(PluginCall call) {
        int port = call.getInt("port", 3000);
        String roomCode = call.getString("roomCode", "UNO");
        String roomName = call.getString("roomName", "Uno Game");

        try {
            // Keep screen on during active hosting
            getActivity().runOnUiThread(() -> {
                getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });

            // Acquire Multicast lock for mDNS / NSD
            acquireMulticastLock();

            // Stop any existing instance
            if (server != null) {
                try { server.stop(); } catch (Exception ignored) {}
            }

            server = new UnoWebSocketServer(port, new UnoWebSocketServer.MessageListener() {
                @Override
                public void onMessageReceived(WebSocket conn, String message) {
                    JSObject ret = new JSObject();
                    ret.put("message", message);
                    notifyListeners("onClientMessage", ret);
                }

                @Override
                public void onClientConnected(WebSocket conn) {
                    JSObject ret = new JSObject();
                    ret.put("remoteAddress", conn.getRemoteSocketAddress().toString());
                    notifyListeners("onClientConnected", ret);
                }

                @Override
                public void onClientDisconnected(WebSocket conn) {
                    JSObject ret = new JSObject();
                    ret.put("remoteAddress", conn.getRemoteSocketAddress().toString());
                    notifyListeners("onClientDisconnected", ret);
                }
            });
            server.start();

            // Register NSD Service
            registerNsdService(roomCode, roomName, port);

            String localIp = getLocalIPv4Address();

            JSObject res = new JSObject();
            res.put("status", "started");
            res.put("ip", localIp);
            res.put("port", port);
            res.put("roomCode", roomCode);
            call.resolve(res);

        } catch (Exception e) {
            call.reject("Failed to start host server: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stopHostServer(PluginCall call) {
        try {
            if (registrationListener != null && nsdManager != null) {
                try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
                registrationListener = null;
            }
            if (server != null) {
                server.stop();
                server = null;
            }
            releaseMulticastLock();

            getActivity().runOnUiThread(() -> {
                getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });

            JSObject res = new JSObject();
            res.put("status", "stopped");
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Failed to stop server: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void discoverRooms(PluginCall call) {
        try {
            acquireMulticastLock();

            if (discoveryListener != null && nsdManager != null) {
                try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            }

            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String regType) {}

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    if (serviceInfo.getServiceType().contains("_uno3d")) {
                        resolveService(serviceInfo);
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                    JSObject ret = new JSObject();
                    ret.put("serviceName", serviceInfo.getServiceName());
                    notifyListeners("onRoomLost", ret);
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {}

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) {}
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) {}
                }
            };

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);

            JSObject res = new JSObject();
            res.put("status", "discovering");
            call.resolve(res);

        } catch (Exception e) {
            call.reject("Discovery failed: " + e.getMessage(), e);
        }
    }

    private void resolveService(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedService) {
                InetAddress host = resolvedService.getHost();
                int port = resolvedService.getPort();
                String ip = (host != null) ? host.getHostAddress() : "";

                JSObject ret = new JSObject();
                ret.put("serviceName", resolvedService.getServiceName());
                ret.put("ip", ip);
                ret.put("port", port);
                notifyListeners("onRoomDiscovered", ret);
            }
        });
    }

    @PluginMethod
    public void stopDiscovery(PluginCall call) {
        try {
            if (discoveryListener != null && nsdManager != null) {
                nsdManager.stopServiceDiscovery(discoveryListener);
                discoveryListener = null;
            }
            releaseMulticastLock();
            JSObject res = new JSObject();
            res.put("status", "stopped");
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Failed to stop discovery: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getNetworkInfo(PluginCall call) {
        String ip = getLocalIPv4Address();
        JSObject res = new JSObject();
        res.put("ip", ip);
        res.put("isHotspot", ip.startsWith("192.168.43."));
        call.resolve(res);
    }

    private void acquireMulticastLock() {
        if (multicastLock == null) {
            WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("UnoLanMulticastLock");
                multicastLock.setReferenceCounted(true);
            }
        }
        if (multicastLock != null && !multicastLock.isHeld()) {
            multicastLock.acquire();
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private void registerNsdService(String roomCode, String roomName, int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("UNO3D_" + roomCode);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {}
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {}
            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private String getLocalIPv4Address() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
