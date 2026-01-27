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
        public ResponseEntity<String> verify(
                @RequestParam(name = "hub.mode", required = false) String mode,
                @RequestParam(name = "hub.challenge", required = false) String challenge,
                @RequestParam(name = "hub.verify_token", required = false) String token) {

            log.info("Webhook verification attempt: mode={}, token={}", mode, token != null ? "<present>" : "<null>");
            if ("subscribe".equals(mode) && verifyToken != null && verifyToken.equals(token)) {
                log.info("Webhook verified successfully.");
                return ResponseEntity.ok(challenge);
            } else {
                log.warn("Webhook verification failed. Provided token did not match.verifyToken : {}, token :{}" ,verifyToken, token);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
            }
        }

        @PostMapping("/webhook")
        public ResponseEntity<String> receive(HttpServletRequest request) throws IOException {
            String body;
           try (Scanner s = new Scanner(request.getInputStream(), StandardCharsets.UTF_8.name())) {
                s.useDelimiter("\\A");
                body = s.hasNext() ? s.next() : "";
            }

            String signatureHeader = request.getHeader("X-Hub-Signature-256");
            log.info("Webhook POST received. signatureHeader={}", signatureHeader != null ? "<present>" : "<missing>");

            // Signature validation (toggleable) TODO: I need to fix toggle for future
            if (signatureCheck) {
                if (appSecret == null || appSecret.isBlank()) {
                    log.error("App secret not provided - cannot validate signature. Set whatsapp.app.secret env var.");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("App secret not configured");
                }
                boolean valid = true;
             /*   try {
                    valid = validateSignature(body, signatureHeader, appSecret);
                } catch (Exception e) {
                    log.error("Signature validation threw: {}", e.getMessage(), e);
                }


                if (!valid) {
                    log.warn("Invalid webhook signature!");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
                }
            } else {
                log.info("Signature checking disabled (signatureCheck=false).");
            }

              */
            }

            try {


                JsonNode root = mapper.readTree(body);
               log.debug("Payload parsed, handing to FlowService.handleIncoming(...)");
                flowService.handleIncoming(root);
            } catch (Exception e) {
                log.error("Error processing webhook payload: {}", e.getMessage(), e);
            }

            return ResponseEntity.ok("EVENT_RECEIVED");
        }

        private boolean validateSignature(String payload, String signatureHeader, String secret) throws Exception {
            if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
                return false;
            }
            String signature = signatureHeader.substring("sha256=".length());

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = Hex.encodeHexString(raw);

            return MessageDigestIsEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        }

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

