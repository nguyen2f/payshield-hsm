package vn.sfin.payshield.hsm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "payshield")
public class PayShieldConfig {
    @NotBlank(message = "HSM host không được để trống")
    private String host;

    @Min(value = 1, message = "Port phải lớn hơn 0")
    @Max(value = 65535, message = "Port phải nhỏ hơn 65536")
    private int port = 1500;

    @Min(value = 1000, message = "Timeout phải ít nhất 1000ms")
    private int timeout = 30000;

    @Min(value = 4, message = "Header length phải là 4")
    @Max(value = 4, message = "Header length phải là 4")
    private int headerLength = 4;

    @Min(value = 1, message = "Max retries phải ít nhất 1")
    private int maxRetries = 3;

    private ConnectionPool connectionPool = new ConnectionPool();

    @Data
    public static class ConnectionPool {
        private boolean enabled = true;

        @Min(value = 1)
        private int minSize = 2;

        @Min(value = 1)
        private int maxSize = 10;

        @Min(value = 60000)
        private long maxIdleTime = 300000;
    }


}
