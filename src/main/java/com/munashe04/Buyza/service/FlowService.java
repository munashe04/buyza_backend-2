package com.munashe04.Buyza.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class FlowService {

    private static final Logger log = LoggerFactory.getLogger(FlowService.class);
    private final WhatsAppService wa;

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

            JsonNode value = entry.get(0).path("changes").get(0).path("value");
            if (value.isMissingNode()) {
                log.debug("No value object found");
                return;
            }

            // Filter out status updates — only process actual messages
            if (value.has("statuses")) {
                log.debug("Ignoring status update webhook");
                return;
            }

            JsonNode messages = value.path("messages");
            if (messages.isMissingNode() || !messages.isArray() || messages.size() == 0) {
                log.debug("No messages found — might be another webhook type");
                return;
            }

            JsonNode msg = messages.get(0);
            String from      = msg.path("from").asText();
            String messageId = msg.path("id").asText();
            String type      = msg.path("type").asText("text");
            String text      = extractText(msg);

            log.info("Received message [{}] from {} type={} -> {}", messageId, from, type, text);
            wa.handleIncomingMessage(from, messageId, type, text);

        } catch (Exception e) {
            log.error("handleIncoming error", e);
        }
    }

    private String extractText(JsonNode msg) {
        if (msg.has("text") && msg.path("text").has("body")) {
            return msg.path("text").path("body").asText();
        }
        if (msg.has("interactive")) {
            JsonNode interactive = msg.path("interactive");
            if (interactive.has("button_reply"))
                return interactive.path("button_reply").path("title").asText();
            if (interactive.has("list_reply"))
                return interactive.path("list_reply").path("title").asText();
        }
        return "";
    }
}