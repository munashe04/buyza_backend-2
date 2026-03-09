package com.munashe04.Buyza.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.munashe04.Buyza.service.FlowService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Scanner;


@RestController
@RequestMapping("/chatbot/webhook")

public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final FlowService flowService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${whatsapp.verify.token}")
    private String verifyToken;

    @Value("${whatsapp.appSecret:}")
    private String appSecret;

    @Value("${whatsapp.signature.check:true}")
    private boolean signatureCheck;

    public WebhookController(FlowService flowService) {
        this.flowService = flowService;
    }

        @GetMapping
        public ResponseEntity<String> verifyWebhook(
                @RequestParam(name = "hub.mode", required = false) String mode,
                @RequestParam(name = "hub.verify_token", required = false) String token,
                @RequestParam(name = "hub.challenge", required = false) String challenge) {

            if ("subscribe".equals(mode) && verifyToken.equals(token)) {
                return ResponseEntity.ok(challenge);
            }

            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
        }


    public ResponseEntity<String> handleWebhook(
            @RequestBody String body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        if (body == null || body.isEmpty()) {
            log.info("Meta test ping");
            return ResponseEntity.ok("OK");
        }

        // LOG EVERYTHING FIRST
        log.info("📨 Real webhook received - body length: {}, signature: {}",
                body.length(), signature != null ? "PRESENT" : "MISSING");

        byte[] rawBody = body.getBytes(StandardCharsets.UTF_8);

        if (signature == null || !verifySignature(rawBody, signature)) {
            log.error("🚨 INVALID SIGNATURE - sig header: {}", signature);
            // TEMPORARILY return 200 to prevent Meta retries while debugging
            return ResponseEntity.ok("EVENT_RECEIVED");
        }

        try {
            JsonNode root = mapper.readTree(body);

            log.info("Webhook payload: {}", body);

            flowService.handleIncoming(root);

            return ResponseEntity.ok("EVENT_RECEIVED");

        } catch (Exception e) {
            log.error("Webhook error", e);
            return ResponseEntity.ok("EVENT_RECEIVED");
        }
    }


    private boolean verifySignature(byte[] payload, String signatureHeader) {
        try {
            if (!signatureHeader.startsWith("sha256=")) return false;

            String received = signatureHeader.substring(7);

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(key);

            byte[] expectedHash = mac.doFinal(payload);
            String expected = Hex.encodeHexString(expectedHash);

            boolean match = MessageDigest.isEqual(
                    received.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8)
            );

            if (!match) {
                log.error("Expected: {}", expected);
                log.error("Received: {}", received);
            }

            return match;

        } catch (Exception e) {
            log.error("Signature error", e);
            return false;
        }
    }

    // ============ MESSAGE ID EXTRACTION ============
    public static String extractMessageId(JsonNode root) {
        try {
            // Path: entry[0].changes[0].value.messages[0].id
            return root.path("entry").get(0)
                    .path("changes").get(0)
                    .path("value").path("messages").get(0)
                    .path("id").asText();
        } catch (Exception e) {
            log.warn("Could not extract message ID: {}", e.getMessage());
            return "unknown";
        }
    }

    // ============ MESSAGE TYPE EXTRACTION ============
    public static String extractMessageType(JsonNode root) {
        try {
            // Path: entry[0].changes[0].value.messages[0].type
            return root.path("entry").get(0)
                    .path("changes").get(0)
                    .path("value").path("messages").get(0)
                    .path("type").asText();
        } catch (Exception e) {
            log.warn("Could not extract message type: {}", e.getMessage());
            return "unknown";
        }
    }

    private boolean isTestNotification(JsonNode root) {
        // Test notifications don't have actual messages
        try {
            boolean hasMessages = root.path("entry").get(0)
                    .path("changes").get(0)
                    .path("value").has("messages");
            return !hasMessages;
        } catch (Exception e) {
            // If we can't find messages, assume it's a test
            return true;
        }
    }
    private void alertSecurityTeam(String message) {
        // Integrate with your alerting system (PagerDuty, Slack, etc.)
        log.error("SECURITY ALERT: {}", message);
        // TODO: Send to monitoring system
    }
    private boolean isMetaTestNotification(String body) {
        try {
            if (body == null || body.isEmpty()) {
                return true; // Empty body = test ping
            }

            // Parse JSON to check structure
            JsonNode root = mapper.readTree(body);

            // Test notifications often have no "entry" array
            boolean hasEntries = root.has("entry") &&
                    root.get("entry").isArray() &&
                    root.get("entry").size() > 0;

            // If it has object but no entries, it's a test
            if (root.has("object") && !hasEntries) {
                return true;
            }

            // Check for test user ID (Meta uses specific IDs for testing)
            if (hasEntries) {
                String from = extractFrom(root);
                if ("1234567890123456".equals(from) ||
                        from.startsWith("test_") ||
                        "test".equals(from)) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            // If can't parse, might be test
            log.warn("Could not parse as JSON, might be test: {}", e.getMessage());
            return true;
        }
    }


    // 🔴 ADD THIS HELPER:
    private String extractFrom(JsonNode root) {
        try {
            return root.path("entry").get(0)
                    .path("changes").get(0)
                    .path("value").path("messages").get(0)
                    .path("from").asText();
        } catch (Exception e) {
            return null;
        }
    }


    // ============ HELPER METHODS ============

    private String readRequestBody(HttpServletRequest request) {
        try (Scanner scanner = new Scanner(
                request.getInputStream(), StandardCharsets.UTF_8.name())) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (IOException e) {
            throw new RuntimeException("Failed to read request body", e);
        }
    }

    // ============ SIGNATURE VALIDATION ============
    private boolean validateSignature(String payload, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        try {
            String receivedSignature = signatureHeader.substring(7);
            String calculatedSignature = calculateHMAC(payload, appSecret);

            // Constant-time comparison for security
            return MessageDigest.isEqual(
                    receivedSignature.getBytes(StandardCharsets.UTF_8),
                    calculatedSignature.getBytes(StandardCharsets.UTF_8)
            );

        } catch (Exception e) {
            log.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private String calculateHMAC(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec spec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(spec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        // Convert to hex
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private boolean isValidSignature(String payload, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            log.warn("Missing or malformed signature header");
            return false;
        }

        try {
            String receivedSignature = signatureHeader.substring(7);
            String calculatedSignature = calculateSignature(payload, appSecret);

            boolean isValid = MessageDigest.isEqual(
                    receivedSignature.getBytes(StandardCharsets.UTF_8),
                    calculatedSignature.getBytes(StandardCharsets.UTF_8)
            );

            if (!isValid) {
                log.warn("Signature mismatch. Expected: {}, Received: {}",
                        calculatedSignature, receivedSignature);
            }

            return isValid;

        } catch (Exception e) {
            log.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private String calculateSignature(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

        return bytesToHex(hash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String extractValue(JsonNode node, String jsonPath) {
        try {
            String[] paths = jsonPath.substring(1).split("/");
            JsonNode current = node;
            for (String path : paths) {
                if (path.matches("\\d+")) {
                    current = current.get(Integer.parseInt(path));
                } else {
                    current = current.path(path);
                }
                if (current.isMissingNode()) {
                    return null;
                }
            }
            return current.asText();
        } catch (Exception e) {
            return null;
        }
    }
}

/*
        private boolean MessageDigestIsEqual(byte[] a, byte[] b) {
            if (a.length != b.length) return false;
            int result = 0;
            for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
            return result == 0;
        }

        @PostMapping("/simulate")
        public ResponseEntity<String> simulate(@RequestBody SimulateRequest reqBody) {
            try {
                String from = reqBody.getFrom();
                String message = reqBody.getMessage();

                String json = String.format("""
                {
                  "object":"whatsapp_business_account",
                  "entry":[
                    {
                      "changes":[
                        {
                          "value":{
                            "messages":[
                              {
                                "from":"Munashe Hondo",
                                "id":"SIMULATED-1",
                                "timestamp":"%d",
                                "text":{"body":"%s"},
                                "type":"text"
                              }
                            ]
                          },
                          "field":"messages"
                        }
                      ]
                    }
                  ]
                }
                """, from, System.currentTimeMillis() / 1000L, message.replace("\"", "\\\""));

                JsonNode root = mapper.readTree(json);
                flowService.handleIncoming(root);
                return ResponseEntity.ok("Simulated event processed");
            } catch (Exception e) {
                log.error("Simulation error: {}", e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Simulation failed");
            }
        }



    @Data
        public static class SimulateRequest {
            private String from = "2714796931";
            private String message = "1";
        }
    }

 */

