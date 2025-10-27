package vn.sfin.payshield.hsm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/hsm")
@RequiredArgsConstructor
public class PayShieldController {

    @Autowired
    PayShieldConnectionService payShieldConnectionService;

    @Autowired
    PayShieldService payShieldService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.info("Health check requested");

        Map<String, Object> response = new HashMap<>();
        boolean connected = payShieldConnectionService.testConnection();

        response.put("connected", connected);
        response.put("status", connected ? "UP" : "DOWN");
        response.put("message", connected ? "HSM is healthy" : "HSM connection failed");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/random/hex")
    public ResponseEntity<Map<String, Object>> generateRandomHex(
            @RequestParam(defaultValue = "16") int length) {

        log.info("Generate random hex - length: {}", length);

        Map<String, Object> response = new HashMap<>();

        String randomData = payShieldService.generateRandomHex(length);

        if (randomData != null) {
            response.put("success", true);
            response.put("data", randomData);
            response.put("length", randomData.length());

            // Add pool status
            PayShieldConnectionPool.PoolStatus poolStatus = payShieldConnectionService.getPoolStatus();
            response.put("poolAvailable", poolStatus.available);

            return ResponseEntity.ok(response);
        } else {
            response.put("success", false);
            response.put("error", "Failed to generate random data");
            return ResponseEntity.internalServerError().body(response);
        }

    }

}
