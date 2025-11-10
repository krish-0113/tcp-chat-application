package com.chatserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatServer {
    // Active clients map: username -> handler
    static final ConcurrentMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    // Executor for handling client threads
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    // Scheduler for periodic tasks (idle timeout)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // Idle timeout seconds (optional feature)
    private final long IDLE_TIMEOUT_SECONDS = 60L;

    private final int port;

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() {
        // Schedule idle checker
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (ClientHandler handler : clients.values()) {
                if (now - handler.getLastActivityMillis() > IDLE_TIMEOUT_SECONDS * 1000L) {
                    // idle: disconnect
                    handler.sendLine("INFO you have been disconnected due to inactivity");
                    handler.shutdown("idle-timeout");
                }
            }
        }, IDLE_TIMEOUT_SECONDS, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Chat server started on port " + port);
            System.out.println("Waiting for clients...");
            while (!Thread.currentThread().isInterrupted()) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket, clients);
                clientPool.execute(handler);
            }
        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        try {
            clientPool.shutdownNow();
            scheduler.shutdownNow();
        } catch (Exception ignored) {}
    }

    public static void broadcast(String message) {
        for (ClientHandler h : clients.values()) {
            h.sendLine(message);
        }
    }

    public static void main(String[] args) {
        int port = 4000; // default
        // allow port via env or arg
        String envPort = System.getenv("CHAT_PORT");
        if (envPort != null) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        ChatServer server = new ChatServer(port);
        server.start();
    }
}
