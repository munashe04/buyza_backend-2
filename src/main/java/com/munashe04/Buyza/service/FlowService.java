package com.munashe04.Buyza.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class FlowService {

    private final WhatsAppService wa;

    private static final Logger log = LoggerFactory.getLogger(FlowService.class);

    public FlowService(WhatsAppService wa) {
        this.wa = wa;
    }
    @Async("webhookExecutor")
    public void handleIncoming(JsonNode root) {
        try {
            JsonNode entry = root.path("entry");
            if (entry.isMissingNode() || !entry.isArray() || entry.size() == 0) {
                log.debug("No entry array found in webhook payload");
                return;
            }

            JsonNode changes = entry.get(0).path("changes");
            if (changes.isMissingNode() || !changes.isArray() || changes.size() == 0) {
                log.debug("No changes array found in webhook payload");
                return;
            }

            JsonNode value = changes.get(0).path("value");
            if (value.isMissingNode()) {
                log.debug("No value object found in webhook payload");
                return;
            }

            JsonNode messages = value.path("messages");
            if (messages.isMissingNode() || !messages.isArray() || messages.size() == 0) {
                log.debug("No messages array found - this might be a status update or other webhook type");
                return;
            }

            JsonNode messageNode = messages.get(0);
            if (messageNode.isMissingNode()) {
                log.debug("Message node is missing");
                return;
            }

            String from = messageNode.path("from").asText();
            String text = extractText(messageNode);

            log.info("Received message from {} -> {}", from, text);
            wa.handleIncomingMessage(from, text);

        } catch (Exception e) {
            log.error("handleIncoming error", e);
        }
    }

    private String extractText(JsonNode messageNode) {
        if (messageNode.has("text") && messageNode.path("text").has("body")) {
            return messageNode.path("text").path("body").asText();
        }
        if (messageNode.has("interactive")) {
            JsonNode interactive = messageNode.path("interactive");
            if (interactive.has("button_reply")) return interactive.path("button_reply").path("title").asText();
            if (interactive.has("list_reply")) return interactive.path("list_reply").path("title").asText();
        }
        return "";
    }
}
