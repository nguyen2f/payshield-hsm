package vn.sfin.payshield.hsm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PayShieldService {

    @Autowired
    PayShieldConnectionService payShieldConnectionService;

    /**
     * Generate random hex
     */
    public String generateRandomHex(int length) {
        log.info("Generating {} random hex characters", length);

        String command = String.format("NOX%03d", length);
        String response = payShieldConnectionService.sendCommand(command);

        if (response == null || response.length() < 8) {
            log.error("Invalid response from HSM");
            return null;
        }

        String responseBody = response.substring(4);
        String responseCode = responseBody.substring(0, 2);
        String errorCode = responseBody.substring(2, 4);

        if (!"NP".equals(responseCode)) {
            log.error("Unexpected response code: {}", responseCode);
            return null;
        }

        if (!"00".equals(errorCode)) {
            log.error("HSM error code: {}", errorCode);
            return null;
        }

        String randomData = responseBody.substring(4);
        log.info("Successfully generated {} hex characters", randomData.length());

        return randomData;
    }

}
