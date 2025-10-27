package vn.sfin.payshield.hsm;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class PayShieldConnectionService {

    @Autowired
    PayShieldConnectionPool payShieldConnectPool;

    private final PayShieldConfig config;

    public PayShieldConnectionService(PayShieldConfig config) {
        this.config = config;
        log.info("PayShield Connection Service initialized - Host: {}, Port: {}",
                config.getHost(), config.getPort());
    }

    public String sendCommand(String command) {
        int retries = 0;
        Exception lastException = null;

        while (retries < config.getMaxRetries()) {
            PayShieldConnectionPool.HSMConnection connection = null;
            try {
                // Lấy connection từ pool
                connection = payShieldConnectPool.borrowConnection();

                // Thực thi command
                String response = executeCommand(connection, command);

                // Trả connection về pool
                payShieldConnectPool.returnConnection(connection);

                return response;

            } catch (Exception e) {
                lastException = e;
                retries++;
                log.warn("Attempt {}/{} failed: {}", retries, config.getMaxRetries(), e.getMessage());

                // Trả connection về pool (sẽ được validate và recreate nếu cần)
                if (connection != null) {
                    payShieldConnectPool.returnConnection(connection);
                }

                if (retries < config.getMaxRetries()) {
                    try {
                        Thread.sleep(1000 * retries); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted during retry", ie);
                        return null;
                    }
                }
            }
        }

        log.error("Failed after {} retries", config.getMaxRetries(), lastException);
        return null;
    }

    /**
     * Thực thi command với connection có sẵn
     */
    private String executeCommand(PayShieldConnectionPool.HSMConnection connection,
                                  String command) throws Exception {
        log.debug("Executing command: {}", command);

        DataOutputStream out = connection.getOutputStream();
        DataInputStream in = connection.getInputStream();

        // Format message với 4-byte hex header
        String fullMessage = formatMessage(command);

        log.debug("Sending command: {}", command);
        log.debug("Full message (with header): {}", fullMessage);

        // Gửi message
        out.write(fullMessage.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        log.debug("Command sent successfully");

        // Đọc response
        String response = readResponse(in);

        log.debug("Response received: {}", response);

        return response;
    }

    /**
     * Format message với 4-byte hex header (uppercase)
     * Ví dụ: "NC" -> "0002NC"
     */
    private String formatMessage(String command) {
        int length = command.length();
        String header = String.format("%04X", length);
        return header + command;
    }

    /**
     * Đọc response từ HSM
     * Response format: [4-byte hex header][response body]
     */
    private String readResponse(DataInputStream in) throws Exception {
        // Đọc header (4 bytes hex)
        byte[] headerBytes = new byte[config.getHeaderLength()];
        in.readFully(headerBytes);

        String header = new String(headerBytes, StandardCharsets.US_ASCII);

        // Parse hex header để lấy message length
        int messageLength = Integer.parseInt(header, 16);

        log.debug("Response header: {} (length: {} bytes)", header, messageLength);

        if (messageLength <= 0 || messageLength > 10000) {
            throw new Exception("Invalid message length: " + messageLength);
        }

        // Đọc message body
        byte[] messageBytes = new byte[messageLength];
        in.readFully(messageBytes);

        String messageBody = new String(messageBytes, StandardCharsets.US_ASCII);

        // Return full response (header + body)
        return header + messageBody;
    }

    /**
     * Test connection tới HSM bằng NC command
     */
    public boolean testConnection() {
        try {
            String response = sendCommand("NC");

            if (response == null || response.length() < 8) {
                log.error("Invalid response from HSM");
                return false;
            }

            String responseBody = response.substring(4);

            if (responseBody.length() < 4) {
                log.error("Response body too short");
                return false;
            }

            String responseCode = responseBody.substring(0, 2);
            String errorCode = responseBody.substring(2, 4);

            boolean isSuccess = "ND".equals(responseCode) && "00".equals(errorCode);

            if (isSuccess) {
                log.info("HSM connection test successful");
            } else {
                log.warn("HSM connection test failed - Response: {}, Error: {}",
                        responseCode, errorCode);
            }

            return isSuccess;

        } catch (Exception e) {
            log.error("HSM connection test failed", e);
            return false;
        }
    }

    /**
     * Get connection pool status
     */
    public PayShieldConnectionPool.PoolStatus getPoolStatus() {
        return payShieldConnectPool.getStatus();
    }

}
