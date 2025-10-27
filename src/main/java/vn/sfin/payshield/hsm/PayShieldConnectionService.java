package vn.sfin.payshield.hsm;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class PayShieldConnectionService {

    private final PayShieldConfig config;

    public PayShieldConnectionService(PayShieldConfig config) {
        this.config = config;
        log.info("PayShield Connection Service initialized - Host: {}, Port: {}",
                config.getHost(), config.getPort());
    }

    /**
     * Gửi command tới PayShield 10K HSM
     * @param command Command cần gửi (không bao gồm header)
     * @return Response từ HSM (bao gồm header)
     */
    public String sendCommand(String command) {
        int retries = 0;
        Exception lastException = null;

        while (retries < config.getMaxRetries()) {
            try {
                return executeCommand(command);
            } catch (Exception e) {
                lastException = e;
                retries++;
                log.warn("Attempt {}/{} failed: {}", retries, config.getMaxRetries(), e.getMessage());

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
     * Thực thi command
     */
    private String executeCommand(String command) {
        log.debug("Connecting to HSM at {}:{}", config.getHost(), config.getPort());

        try (Socket socket = new Socket(config.getHost(), config.getPort())) {
            socket.setSoTimeout(config.getTimeout());
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

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

        } catch (Exception e) {
            log.error("Error executing command: {}", e.getMessage());
            throw new RuntimeException("HSM communication error", e);
        }
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
    private String readResponse(DataInputStream in) {
        try {
            // Đọc header (4 bytes hex)
            byte[] headerBytes = new byte[config.getHeaderLength()];
            in.readFully(headerBytes);

            String header = new String(headerBytes, StandardCharsets.US_ASCII);

            // Parse hex header để lấy message length
            int messageLength = Integer.parseInt(header, 16);

            log.debug("Response header: {} (length: {} bytes)", header, messageLength);

            if (messageLength <= 0 || messageLength > 10000) {
                log.error("Invalid message length: {}", messageLength);
                return null;
            }

            // Đọc message body
            byte[] messageBytes = new byte[messageLength];
            in.readFully(messageBytes);

            String messageBody = new String(messageBytes, StandardCharsets.US_ASCII);

            // Return full response (header + body)
            return header + messageBody;

        } catch (Exception e) {
            log.error("Error reading response: {}", e.getMessage());
            return null;
        }
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
}
