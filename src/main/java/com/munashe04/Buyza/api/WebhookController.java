package com.munashe04.Buyza.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.munashe04.Buyza.service.FlowService;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/chatbot/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final FlowService    flowService;
    private final ObjectMapper   mapper = new ObjectMapper();

    @Value("${whatsapp.verify.token}")
    private String verifyToken;

    @Value("${whatsapp.appSecret:}")
    private String appSecret;

    @Value("${whatsapp.signature.check:true}")
    private boolean signatureCheck;

    public WebhookController(FlowService flowService) {
        this.flowService = flowService;
    }

    // ============ WEBHOOK VERIFICATION ============

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode",         required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge",    required = false) String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("Webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }
        log.warn("Webhook verification failed — mode={}, token={}", mode, token);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    // ============ MESSAGE PROCESSING ============

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestBody String body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        if (body == null || body.isEmpty()) {
            log.info("Empty ping received");
            return ResponseEntity.ok("OK");
        }

        log.info("📨 Webhook received - length: {}, signature: {}",
                body.length(), signature != null ? "PRESENT" : "MISSING");

        // Signature validation
        if (signatureCheck) {
            if (signature == null) {
                log.error("🚨 Missing signature header");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing signature");
            }
            if (!verifySignature(body.getBytes(StandardCharsets.UTF_8), signature)) {
                log.error("🚨 Invalid signature");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
            }
            log.debug("✅ Signature validated");
        }

        try {
            JsonNode root = mapper.readTree(body);
            flowService.handleIncoming(root);
            return ResponseEntity.ok("EVENT_RECEIVED");
        } catch (Exception e) {
            log.error("Webhook processing error", e);
            return ResponseEntity.ok("EVENT_RECEIVED"); // always 200 to prevent Meta retries
        }
    }

    // ============ SIGNATURE VERIFICATION ============

    private boolean verifySignature(byte[] payload, String signatureHeader) {
        try {
            if (!signatureHeader.startsWith("sha256=")) return false;

            String received = signatureHeader.substring(7);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            String expected = Hex.encodeHexString(mac.doFinal(payload));

            boolean match = MessageDigest.isEqual(
                    received.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));

            if (!match) {
                log.error("Signature mismatch — expected: {}, received: {}", expected, received);
            }
            return match;

        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }
}