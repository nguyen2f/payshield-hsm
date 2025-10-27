package vn.sfin.payshield.hsm;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PayShieldConnectionPool {

    @Autowired
    PayShieldConfig config;

    private BlockingQueue<HSMConnection> availableConnections;
    private BlockingQueue<HSMConnection> usedConnections;
    private final int POOL_SIZE = 5;
    private final int BORROW_TIMEOUT = 5; // seconds


    @PostConstruct
    public void initialize() {
        log.info("Initializing HSM Connection Pool - Size: {}", POOL_SIZE);

        availableConnections = new ArrayBlockingQueue<>(POOL_SIZE);
        usedConnections = new ArrayBlockingQueue<>(POOL_SIZE);

        // Tạo connections
        for (int i = 0; i < POOL_SIZE; i++) {
            try {
                HSMConnection connection = createConnection();
                availableConnections.offer(connection);
                log.info("Created HSM connection {}/{}", i + 1, POOL_SIZE);
            } catch (Exception e) {
                log.error("Failed to create HSM connection {}/{}: {}",
                        i + 1, POOL_SIZE, e.getMessage());
            }
        }

        log.info("HSM Connection Pool initialized with {} connections",
                availableConnections.size());
    }

    /**
     * Tạo connection mới tới HSM
     */
    private HSMConnection createConnection() throws IOException {
        Socket socket = new Socket(config.getHost(), config.getPort());
        socket.setSoTimeout(config.getTimeout());
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);

        DataInputStream inputStream = new DataInputStream(socket.getInputStream());
        DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());

        return new HSMConnection(socket, inputStream, outputStream);
    }

    /**
     * Lấy connection từ pool
     */
    public HSMConnection borrowConnection() throws InterruptedException, IOException {
        log.debug("Borrowing connection from pool. Available: {}", availableConnections.size());

        HSMConnection connection = availableConnections.poll(BORROW_TIMEOUT, TimeUnit.SECONDS);

        if (connection == null) {
            log.error("No available connection in pool after {} seconds", BORROW_TIMEOUT);
            throw new IOException("Connection pool exhausted - timeout after " + BORROW_TIMEOUT + "s");
        }

        // Kiểm tra connection còn valid không
        if (!connection.isValid()) {
            log.warn("Invalid connection detected, creating new one");
            try {
                connection.close();
            } catch (Exception e) {
                log.error("Error closing invalid connection", e);
            }
            connection = createConnection();
        }

        // Refresh last used time
        connection.updateLastUsed();

        // Move to used pool
        usedConnections.offer(connection);

        log.debug("Connection borrowed. Available: {}, Used: {}",
                availableConnections.size(), usedConnections.size());

        return connection;
    }

    /**
     * Trả connection về pool
     */
    public void returnConnection(HSMConnection connection) {
        if (connection == null) {
            return;
        }

        // Remove from used pool
        usedConnections.remove(connection);

        // Kiểm tra connection còn valid không
        if (connection.isValid()) {
            connection.updateLastUsed();
            availableConnections.offer(connection);
            log.debug("Connection returned. Available: {}, Used: {}",
                    availableConnections.size(), usedConnections.size());
        } else {
            log.warn("Returning invalid connection, creating new one");
            try {
                connection.close();
                HSMConnection newConnection = createConnection();
                availableConnections.offer(newConnection);
            } catch (IOException e) {
                log.error("Failed to recreate connection", e);
            }
        }
    }

    /**
     * Đóng tất cả connections khi shutdown
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down HSM Connection Pool");

        // Close available connections
        for (HSMConnection conn : availableConnections) {
            try {
                conn.close();
            } catch (IOException e) {
                log.error("Error closing connection", e);
            }
        }

        // Close used connections
        for (HSMConnection conn : usedConnections) {
            try {
                conn.close();
            } catch (IOException e) {
                log.error("Error closing connection", e);
            }
        }

        availableConnections.clear();
        usedConnections.clear();

        log.info("HSM Connection Pool shut down");
    }

    /**
     * Get pool status
     */
    public PoolStatus getStatus() {
        return new PoolStatus(
                POOL_SIZE,
                availableConnections.size(),
                usedConnections.size()
        );
    }

    /**
     * HSM Connection wrapper
     */
    public static class HSMConnection {
        private final Socket socket;
        private final DataInputStream inputStream;
        private final DataOutputStream outputStream;
        private long lastUsed;
        private final long createdAt;

        public HSMConnection(Socket socket, DataInputStream inputStream,
                             DataOutputStream outputStream) {
            this.socket = socket;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.lastUsed = System.currentTimeMillis();
            this.createdAt = System.currentTimeMillis();
        }

        public DataInputStream getInputStream() {
            return inputStream;
        }

        public DataOutputStream getOutputStream() {
            return outputStream;
        }

        public void updateLastUsed() {
            this.lastUsed = System.currentTimeMillis();
        }

        public boolean isValid() {
            if (socket == null || socket.isClosed()) {
                return false;
            }

            // Check if connection is too old (30 minutes)
            long age = System.currentTimeMillis() - createdAt;
            if (age > 1800000) {
                return false;
            }

            // Check if idle too long (5 minutes)
            long idleTime = System.currentTimeMillis() - lastUsed;
            if (idleTime > 300000) {
                return false;
            }

            return socket.isConnected()
                    && !socket.isInputShutdown()
                    && !socket.isOutputShutdown();
        }

        public void close() throws IOException {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Pool status info
     */
    public static class PoolStatus {
        public final int totalSize;
        public final int available;
        public final int used;

        public PoolStatus(int totalSize, int available, int used) {
            this.totalSize = totalSize;
            this.available = available;
            this.used = used;
        }
    }

}
