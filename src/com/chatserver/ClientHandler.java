package com.chatserver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.ConcurrentMap;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final ConcurrentMap<String, ClientHandler> clients;
    private volatile String username = null;
    private volatile long lastActivityMillis = System.currentTimeMillis();

    private BufferedReader in;
    private BufferedWriter out;
    private volatile boolean running = true;

    public ClientHandler(Socket socket, ConcurrentMap<String, ClientHandler> clients) {
        this.socket = socket;
        this.clients = clients;
    }

    public long getLastActivityMillis() {
        return lastActivityMillis;
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

            // Expect LOGIN <username> first
            String line = in.readLine();
            if (line == null) {
                shutdown("no-login");
                return;
            }
            line = line.trim();
            if (!line.toUpperCase().startsWith("LOGIN ")) {
                sendLine("ERR invalid-protocol");
                shutdown("protocol");
                return;
            }
            String requested = line.substring(6).trim();
            if (requested.isEmpty() || requested.contains(" ")) {
                sendLine("ERR invalid-username");
                shutdown("protocol");
                return;
            }

            // Check username uniqueness atomically
            synchronized (clients) {
                if (clients.containsKey(requested)) {
                    sendLine("ERR username-taken");
                    shutdown("username-taken");
                    return;
                } else {
                    this.username = requested;
                    clients.put(username, this);
                }
            }

            sendLine("OK");
            broadcastInfo(username + " connected");

            // main loop
            String input;
            while (running && (input = in.readLine()) != null) {
                lastActivityMillis = System.currentTimeMillis();

                input = input.trim();
                if (input.isEmpty()) continue;

                // Split by space on command token
                String[] parts = input.split("\\s+", 2);
                String cmd = parts[0].toUpperCase();
                String rest = parts.length > 1 ? parts[1] : "";

                switch (cmd) {
                    case "MSG":
                        // Broadcast message: MSG <username> <text>
                        String text = rest.trim();
                        if (text.isEmpty()) {
                            sendLine("ERR empty-message");
                            break;
                        }
                        String safeText = normalizeSpaces(text);
                        ChatServer.broadcast("MSG " + username + " " + safeText);
                        break;

                    case "WHO":
                        // List active users: USER <username> per connected user
                        for (String user : clients.keySet()) {
                            sendLine("USER " + user);
                        }
                        break;

                    case "DM":
                        // DM <username> <text>
                        String[] dmParts = rest.split("\\s+", 2);
                        if (dmParts.length < 2) {
                            sendLine("ERR dm-invalid");
                            break;
                        }
                        String target = dmParts[0];
                        String dmText = dmParts[1].trim();
                        ClientHandler targetHandler = clients.get(target);
                        if (targetHandler == null) {
                            sendLine("ERR user-not-found");
                        } else {
                            targetHandler.sendLine("DM " + username + " " + normalizeSpaces(dmText));
                            // optional: inform sender
                            sendLine("OK");
                        }
                        break;

                    case "PING":
                        sendLine("PONG");
                        break;

                    case "LOGOUT":
                        sendLine("OK");
                        shutdown("logout");
                        break;

                    default:
                        sendLine("ERR unknown-command");
                }
            }
        } catch (IOException e) {
            // client likely disconnected unexpectedly
        } finally {
            shutdown("io-exit");
        }
    }

    public void sendLine(String line) {
        try {
            synchronized (out) {
                out.write(line);
                out.write("\r\n");
                out.flush();
            }
        } catch (IOException e) {
            // unable to send: shutdown
            shutdown("send-failed");
        }
    }

    private void broadcastInfo(String infoMessage) {
        // INFO <message>
        ChatServer.broadcast("INFO " + infoMessage);
    }

    // normalize spaces: collapse multiple spaces/newlines
    private String normalizeSpaces(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Shutdown this client handler and close resources.
     * If username was registered, remove from clients and notify others.
     */
    public void shutdown(String reason) {
        if (!running) return;
        running = false;

        try {
            if (username != null) {
                // remove from active map
                clients.remove(username);
                // notify remaining
                ChatServer.broadcast("INFO " + username + " disconnected");
            }
        } catch (Exception ignored) {}

        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}
