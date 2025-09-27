package com.munashe04.Buyza.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Objects;

/**
 * WhatsAppService - simplified user-friendly flow (no fee calculations).
 * - Shows friendly menu
 * - Handles 1=Online Order, 2=Assisted Order, 3=Delivery Info, 4=Talk to Agent
 * - After user submits details, replies: "An agent will get back to you with a final quote."
 * - Asks a confirmation question (Would you like to proceed once you receive the quote? Yes/No)
 * - Logs all interactions to Google Sheets via GoogleSheetsService
 */
@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    private final RestTemplate restTemplate;
    private final GoogleSheetsService sheetsService;

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    public WhatsAppService(RestTemplate restTemplate, GoogleSheetsService sheetsService) {
        this.restTemplate = restTemplate;
        this.sheetsService = sheetsService;
    }

    // central entry called by FlowService
    public void handleIncomingMessage(String from, String rawText) {
        String text = rawText == null ? "" : rawText.trim();

        if (isGreeting(text)) {
            sendMainMenu(from);
            safeLog("Greeting", from, text, "-", "Active");
            return;
        }

        String cmd = text.trim();

        switch (cmd) {
            case "1":
                sendOnlineOrderPrompt(from);
                safeLog("Online Order Start", from, text, "-", "New Order");
                return;

            case "2":
                sendAssistedOrderPrompt(from);
                safeLog("Assisted Order Start", from, text, "-", "New Order");
                return;

            case "3":
                sendDeliveryInfo(from);
                safeLog("Delivery Info", from, text, "-", "Info Provided");
                return;

            case "4":
                sendTalkToAgent(from);
                safeLog("Agent Request", from, text, "-", "Escalated");
                return;

            default:
                handleFreeText(from, text);
        }
    }

    /* ---------------- prompts ---------------- */

    private void sendMainMenu(String to) {
        String menu = """
                👋🏾 Hi there — welcome to *Buyza!*  

                We help you buy from major retailers in South Africa and deliver to Zimbabwe. 🇿🇦➡️🇿🇼

                Please reply with a number to get started:
                1️⃣ Online Order – You already know what you want and have a cart/product link
                2️⃣ Assisted Order – You want help choosing or finding products
                3️⃣ Delivery Info – View delivery locations, costs & timelines
                4️⃣ Talk to an Agent – Chat directly with a team member 👩🏾‍💻

                Tip: reply *Menu* any time to see this menu again.
                """;
        sendMessageWithRetry(to, menu);
    }

    private void sendOnlineOrderPrompt(String to) {
        String msg = """
                🛒 *Online Order* selected ✅

                Please send:
                • 🛒 The link to your cart or product(s)
                • 💰 Total value of goods

                Example:
                Cart link: https://www.takealot.com/...
                Total: R850

                After you send, we'll confirm we received it and an agent will provide a final quote.
                """;
        sendMessageWithRetry(to, msg);
    }

    private void sendAssistedOrderPrompt(String to) {
        String msg = """
                🛍️ *Assisted Order* selected ✅

                Please tell us:
                • Item name or description
                • Budget
                • Any preferences (brand, colour, features, model, year)

                Example:
                "I need a replacement gearbox for a 2019 Mercedes-Benz GLC, budget R8 000"
                
                Our team will research and send back a quote within 3 working days.
                """;
        sendMessageWithRetry(to, msg);
    }

    private void sendDeliveryInfo(String to) {
        String msg = """
                🚚 *Delivery Information*

                • Orders delivered weekly or bi-weekly to Zimbabwe.
                • Delivery points: Harare, Bulawayo, Gweru, Mutare, Masvingo, Chinhoyi.
                • Door-to-door delivery available in some areas (extra charge).

                📄 View full delivery costs & timelines here: [Google Sheet Link]
                """;
        sendMessageWithRetry(to, msg);
    }

    private void sendTalkToAgent(String to) {
        String msg = """
                👩🏾‍💻 *Talk to an Agent*

                ✅ Thank you — an agent will assist you as soon as possible.
                ⏰ Typical response time: within a few hours (may be longer during busy times).

                If you'd like priority handling, reply "Priority" and an agent will flag it.
                """;
        sendMessageWithRetry(to, msg);
    }

    /* ---------------- free text handler ---------------- */

    private void handleFreeText(String from, String text) {
        if (looksLikeCartSubmission(text)) {
            // user sent cart + total
            sendMessageWithRetry(from, """
                    ✅ Thank you! We’ve received your cart details.
                    
                    An agent will get back to you with your final quote. 
                    
                    Would you like to proceed with this order once you receive the quote? (Reply Yes or No)
                    """);
            safeLog("Online Order Details", from, text, "-", "Details Provided");
            return;
        }

        if (looksLikeAssistedRequest(text)) {
            sendMessageWithRetry(from, """
                    ✅ Thanks — we received your request.

                    Our team will review and reply with a quote within 3 working days.

                    Would you like to proceed with this order once you receive the quote? (Reply Yes or No)
                    """);
            safeLog("Assisted Order Details", from, text, "-", "Details Provided");
            return;
        }

        // quote confirmation (for both flows)
        if (text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("y")) {
            sendMessageWithRetry(from, """
                    ✅ Perfect! A payment link will be sent to you shortly once the agent prepares it.
                    
                    If you've already paid, reply "Paid" and include payment reference.
                    """);
            safeLog("Quote Accepted", from, text, "-", "Awaiting Payment");
            return;
        }

        if (text.equalsIgnoreCase("no") || text.equalsIgnoreCase("n")) {
            sendMessageWithRetry(from, """
                    👌 No problem — your request has been cancelled.

                    You’re welcome to shop with us anytime. Reply *Menu* to start again.
                    """);
            safeLog("Quote Rejected", from, text, "-", "Cancelled");
            return;
        }

        // payment confirmation
        if (text.toLowerCase().startsWith("paid") || text.toLowerCase().contains("payment")) {
            sendMessageWithRetry(from, """
                    💰 Thanks — we recorded your payment intent. An agent will confirm and process your order.
                    """);
            safeLog("Payment Notice", from, text, "-", "Payment Noted");
            return;
        }

        // small-help keywords
        if (text.equalsIgnoreCase("menu") || text.equalsIgnoreCase("0")) {
            sendMainMenu(from);
            return;
        }

        // fallback
        sendMessageWithRetry(from, """
                ❓ Sorry, I didn't understand that.

                Reply with a number:
                1️⃣ Online Order
                2️⃣ Assisted Order
                3️⃣ Delivery Info
                4️⃣ Talk to an Agent
                or reply *Menu* to see the menu.
                """);
    }

    /* ---------------- helpers ---------------- */

    private boolean isGreeting(String text) {
        if (text == null) return false;
        String lower = text.trim().toLowerCase();
        return lower.equals("hi") || lower.equals("hello") || lower.equals("hey") || lower.equals("start");
    }

    private boolean looksLikeCartSubmission(String text) {
        if (text == null) return false;
        String low = text.toLowerCase();
        return (low.contains("cart") || low.contains("cart link") || low.contains("takealot") || low.contains("total:") || low.matches(".*\\br\\s?\\d+.*"));
    }

    private boolean looksLikeAssistedRequest(String text) {
        if (text == null) return false;
        String low = text.toLowerCase();
        return low.contains("need") || low.contains("help") || low.contains("looking for") || low.contains("budget") || low.contains("want");
    }

    private void safeLog(String type, String phone, String message, String quote, String status) {
        try {
            sheetsService.saveInteraction(type, phone, message, quote, status);
        } catch (Exception e) {
            log.warn("Failed to save interaction to sheets: {}", e.getMessage());
        }
    }

    /* -------------- WhatsApp send helpers -------------- */

    private void sendMessageWithRetry(String to, String body) {
        int tries = 0, max = 3;
        while (tries < max) {
            try {
                sendMessage(to, body);
                return;
            } catch (RuntimeException e) {
                tries++;
                log.warn("Send attempt #{} failed for {}: {}", tries, to, e.getMessage());
                try { Thread.sleep(400L * tries); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        log.error("Failed to send message to {} after {} tries", to, max);
    }

    private void sendMessage(String to, String body) {
        if (Objects.isNull(phoneNumberId) || phoneNumberId.isBlank()) {
            log.error("phoneNumberId not configured - cannot send message");
            throw new IllegalStateException("phoneNumberId not configured");
        }
        if (Objects.isNull(accessToken) || accessToken.isBlank()) {
            log.error("accessToken not configured - cannot send message");
            throw new IllegalStateException("accessToken not configured");
        }

        String url = String.format("https://graph.facebook.com/v19.0/%s/messages", phoneNumberId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", body)
        );

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, req, String.class);
            log.info("Sent WhatsApp message to {} -> HTTP {}", to, resp.getStatusCodeValue());
        } catch (HttpClientErrorException e) {
            log.error("WhatsApp API error: {} -> {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("WhatsApp API error " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Error sending message: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
