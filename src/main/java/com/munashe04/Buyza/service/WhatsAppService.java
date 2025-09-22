package com.munashe04.Buyza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    public void handleIncomingMessage(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode messages = json.at("/entry/0/changes/0/value/messages");

            if (messages.isMissingNode() || !messages.isArray()) {
                log.info("No messages found in webhook.");
                return;
            }

            for (JsonNode message : messages) {
                String from = message.get("from").asText();
                String text = message.at("/text/body").asText();
                log.info("Incoming message from {}: {}", from, text);

                // For now simple echo bot
                sendMessage(from, "You said: " + text);
            }
        } catch (Exception e) {
            log.error("Failed to parse webhook body: {}", e.getMessage(), e);
            throw new RuntimeException("Webhook parsing failed", e);
        }
    }

   /* public void sendMessage(String to, String message) {
        String url = String.format("https://graph.facebook.com/v19.0/%s/messages", phoneNumberId);

        String payload = """
                {
                  "messaging_product": "whatsapp",
                  "to": "%s",
                  "text": { "body": "%s" }
                }
                """.formatted(to, message);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<String> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Message sent successfully to {}", to);
            } else {
                log.error("Failed to send message. Status: {}, Body: {}",
                        response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Error sending WhatsApp message: {}", e.getMessage(), e);
        }
    }

    */

    public void sendMessage(String to, String message) {
        String url = "https://graph.facebook.com/v19.0/" + phoneNumberId + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");  // 🔴 REQUIRED
        payload.put("to", to);
        payload.put("type", "text");

        Map<String, String> text = new HashMap<>();
        text.put("body", message);
        payload.put("text", text);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("Message sent to {}: {}", to, response.getBody());
        } catch (HttpClientErrorException e) {
            log.error("Error sending WhatsApp message: {}", e.getResponseBodyAsString(), e);
        }
    }
}
