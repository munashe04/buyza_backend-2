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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Scanner;


@RestController
@RequestMapping("/chatbot")

public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final FlowService flowService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${whatsapp.verify.token}")
    private String verifyToken;

    @Value("${whatsapp.access-token:}")
    private String appSecret;

    @Value("${whatsapp.signature.check:true}")
    private boolean signatureCheck;

    public WebhookController(FlowService flowService) {
        this.flowService = flowService;
    }

    @GetMapping("/webhook")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode") String mode,
            @RequestParam(name = "hub.challenge") String challenge,
            @RequestParam(name = "hub.verify_token") String token) {

        log.info("Webhook verification - Mode: {}, Token present: {}",
                mode, token != null);

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("✅ Webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }

        log.warn("❌ Webhook verification failed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    // ============ MESSAGE PROCESSING ENDPOINT ============
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        long startTime = System.currentTimeMillis();
        String requestBody = readRequestBody(request);

        // Log incoming request (trimmed for security)
        log.info("📨 Webhook received - Signature: {}, Length: {} chars",
                signature != null ? "present" : "MISSING",
                requestBody.length());

        // 🔴 CRITICAL FIX: Check if this is a TEST notification
        if (isMetaTestNotification(requestBody)) {
            log.info("✅ Meta test notification received - No signature expected");
            return ResponseEntity.ok("TEST_OK");
        }

        // 🔴 CRITICAL FIX: Only validate signature for REAL messages
        if (signature == null || signature.isEmpty()) {
            log.error("🚨 REAL MESSAGE WITHOUT SIGNATURE - Rejecting");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Missing signature");
        }

        // 1. VALIDATE SIGNATURE (CRITICAL FOR PRODUCTION)
        if (signatureCheck) {
            if (!isValidSignature(requestBody, signature)) {
                log.error("🚨 INVALID SIGNATURE - Possible security breach");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid signature");
            }
            log.debug("✅ Signature validated");
        }

        // 2. PARSE AND PROCESS ASYNCHRONOUSLY (for speed)
        try {
            JsonNode payload = mapper.readTree(requestBody);

            // Extract basic info for logging
            String messageId = extractValue(payload,
                    "/entry/0/changes/0/value/messages/0/id");
            String from = extractValue(payload,
                    "/entry/0/changes/0/value/messages/0/from");
            String messageType = extractValue(payload,
                    "/entry/0/changes/0/value/messages/0/type");

            log.info("📱 Message [{}] from {} - Type: {}",
                    messageId, from, messageType);

            // 3. PROCESS IN BACKGROUND (Critical for < 2s response)
            flowService.handleIncoming(payload);

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("⚡ Webhook processed in {}ms", processingTime);

            // Always return 200 immediately
            return ResponseEntity.ok("EVENT_RECEIVED");

        } catch (Exception e) {
            log.error("❌ Error processing webhook: {}", e.getMessage(), e);
            // STILL return 200 so Meta doesn't retry
            return ResponseEntity.ok("EVENT_RECEIVED");
        }
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

