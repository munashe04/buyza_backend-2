package com.munashe04.Buyza.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    // ============ CONSTANTS ============
    private static final long MESSAGE_DEDUP_WINDOW_SECS = 300;
    private static final int  MAX_MESSAGES_PER_MINUTE   = 10;
    private static final int  MAX_LOG_MESSAGE_LENGTH    = 500;

    // ============ SESSION STATE ============
    public enum State {
        NONE,
        AWAITING_ONLINE_ORDER_DETAILS,
        AWAITING_ASSISTED_ORDER_DETAILS,
        AWAITING_ASSISTED_QUOTE_CONFIRMATION,
        AWAITING_QUOTE_CONFIRMATION,
        AWAITING_PAYMENT,
        TALKING_TO_AGENT
    }

    // ============ IN-MEMORY STORES ============
    private final Map<String, Long>       processedMessages = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> rateLimitMap      = new ConcurrentHashMap<>();

    // ============ DEPENDENCIES ============
    private final RestTemplate        restTemplate;
    private final GoogleSheetsService sheetsService;
    private final FaqService          faqService;
    private final SessionService      sessionService;

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.token}")
    private String accessToken;

    @Value("${slack.webhook.url:}")
    private String slackWebhookUrl;

    public WhatsAppService(RestTemplate restTemplate,
                           GoogleSheetsService sheetsService,
                           FaqService faqService,
                           SessionService sessionService) {
        this.restTemplate   = restTemplate;
        this.sheetsService  = sheetsService;
        this.faqService     = faqService;
        this.sessionService = sessionService;
    }

    // ============ MAIN ENTRY POINT ============

    public void handleIncomingMessage(String from, String messageId,
                                      String type, String rawText) {
        // 1. Deduplicate
        if (isDuplicate(messageId)) {
            log.info("Duplicate message {} from {} — ignoring", messageId, from);
            return;
        }

        // 2. Rate limit
        if (isRateLimited(from)) {
            log.warn("Rate limit hit for {} — ignoring", from);
            return;
        }

        // 3. Non-text messages
        if (!type.equals("text")) {
            handleNonTextMessage(from, type);
            return;
        }

        String text         = rawText == null ? "" : rawText.trim();
        State  currentState = sessionService.getState(from);

        log.info("User {} | State: {} | Message: {}", from, currentState, text);

        // 4. Global commands — always available
        if (text.equalsIgnoreCase("menu") || text.equals("0")) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }

        // 5. Route based on state
        switch (currentState) {
            case AWAITING_ONLINE_ORDER_DETAILS        -> handleOnlineOrderDetails(from, text);
            case AWAITING_ASSISTED_ORDER_DETAILS      -> handleAssistedOrderDetails(from, text);
            case AWAITING_ASSISTED_QUOTE_CONFIRMATION -> handleAssistedQuoteConfirmation(from, text);
            case AWAITING_QUOTE_CONFIRMATION          -> handleQuoteConfirmation(from, text);
            case AWAITING_PAYMENT                     -> handlePaymentConfirmation(from, text);
            case TALKING_TO_AGENT                     -> handleTalkingToAgent(from, text);
            default                                   -> handleMenuSelection(from, text);
        }
    }

    // ============ NON-TEXT HANDLER ============

    private void handleNonTextMessage(String from, String type) {
        String response = switch (type) {
            case "image", "document", "video" ->
                    "📎 Thanks for sending that! Unfortunately our bot can't process files yet.\n\n" +
                            "Reply *4* to talk to an agent who can help, or reply *Menu* to see options.";
            case "audio", "voice" ->
                    "🎙️ Voice notes aren't supported yet.\n\n" +
                            "Please type your message or reply *Menu* to see options.";
            case "sticker", "reaction" ->
                    "😊 Thanks! Reply *Menu* to see what we can help you with.";
            case "location" ->
                    "📍 Thanks for sharing your location!\n\n" +
                            "Reply *3* for delivery info or *Menu* for all options.";
            default ->
                    "Sorry, I can't process that type of message.\n\nReply *Menu* to see options.";
        };
        sendMessageWithRetry(from, response);
    }

    // ============ MENU SELECTION ============

    private void handleMenuSelection(String from, String text) {
        if (isGreeting(text)) {
            sendMainMenu(from);
            safeLog("Greeting", from, text, "-", "Active");
            return;
        }

        switch (text) {
            case "1" -> {
                sessionService.setState(from, State.AWAITING_ONLINE_ORDER_DETAILS);
                sendOnlineOrderPrompt(from);
                safeLog("Online Order Start", from, text, "-", "New Order");
            }
            case "2" -> {
                sessionService.setState(from, State.AWAITING_ASSISTED_ORDER_DETAILS);
                sendAssistedOrderPrompt(from);
                safeLog("Assisted Order Start", from, text, "-", "New Order");
            }
            case "3" -> {
                sendDeliveryInfo(from);
                safeLog("Delivery Info", from, text, "-", "Info Provided");
            }
            case "4" -> {
                sessionService.setState(from, State.TALKING_TO_AGENT);
                sendTalkToAgent(from);
                safeLog("Agent Request", from, text, "-", "Escalated");
            }
            default -> {
                if (faqService.isFaqQuestion(text)) {
                    sendMessageWithRetry(from, faqService.answer(text));
                    safeLog("FAQ", from, text, "-", "FAQ Answered");
                } else {
                    sendFallback(from);
                }
            }
        }
    }

    // ============ ONLINE ORDER FLOW ============

    private void handleOnlineOrderDetails(String from, String text) {
        if (isBackCommand(text)) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        sendMessageWithRetry(from, """
                ✅ Thank you! We've received your cart details.

                An agent will review and send you a final quote shortly.

                Would you like to proceed with this order once you receive the quote?
                Reply *Yes* to confirm or *No* to cancel.
                (Reply *Menu* anytime to start over)
                """);
        sessionService.setState(from, State.AWAITING_QUOTE_CONFIRMATION);
        safeLog("Online Order Details", from, text, "-", "Details Provided");
    }

    private void handleQuoteConfirmation(String from, String text) {
        if (isBackCommand(text)) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        if (text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("y")) {
            sendMessageWithRetry(from, """
                    ✅ Perfect! A payment link will be sent to you shortly.

                    Once you receive it, complete the payment and reply:
                    *Paid* followed by your reference number.
                    Example: "Paid REF123456"
                    """);
            sessionService.setState(from, State.AWAITING_PAYMENT);
            safeLog("Online Quote Accepted", from, text, "-", "Awaiting Payment");

        } else if (text.equalsIgnoreCase("no") || text.equalsIgnoreCase("n")) {
            sendMessageWithRetry(from, """
                    👌 No problem — your request has been cancelled.

                    Reply *Menu* to start a new order anytime.
                    """);
            sessionService.setState(from, State.NONE);
            safeLog("Online Quote Rejected", from, text, "-", "Cancelled");

        } else {
            sendMessageWithRetry(from,
                    "Please reply *Yes* to confirm or *No* to cancel.\n" +
                            "(Reply *Menu* to start over)");
        }
    }

    // ============ ASSISTED ORDER FLOW ============

    private void handleAssistedOrderDetails(String from, String text) {
        if (isBackCommand(text)) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        sendMessageWithRetry(from, """
                ✅ Thanks — we've received your request!

                Our team will research and send you a quote within 3 working days.
                We'll message you right here on WhatsApp with the quote.

                Once you receive our quote, reply *Quote* to let us know 
                you're ready to review it and we'll walk you through next steps.

                Reply *Menu* anytime to explore other options.
                """);
        sessionService.setState(from, State.AWAITING_ASSISTED_QUOTE_CONFIRMATION);
        safeLog("Assisted Order Details", from, text, "-", "Details Provided");
    }

    private void handleAssistedQuoteConfirmation(String from, String text) {
        if (isBackCommand(text)) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }

        // Agent has sent quote externally — user replies "Quote" to trigger next step
        if (text.equalsIgnoreCase("quote") || text.equalsIgnoreCase("received")) {
            sendMessageWithRetry(from, """
                    📋 Great — you've received your quote!

                    Would you like to proceed with this order?
                    Reply *Yes* to confirm or *No* to cancel.
                    """);
            sessionService.setState(from, State.AWAITING_QUOTE_CONFIRMATION);
            safeLog("Assisted Quote Received", from, text, "-", "Quote Acknowledged");
            return;
        }

        // User might be asking questions or sending follow-up messages
        sendMessageWithRetry(from, """
                ⏳ Our team is preparing your quote — it will arrive within 3 working days.

                Once you receive it, reply *Quote* and we'll guide you through the next steps.

                Reply *Menu* to go back or *4* to talk to an agent.
                """);
    }

    // ============ PAYMENT FLOW ============

    private void handlePaymentConfirmation(String from, String text) {
        if (isBackCommand(text)) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        if (text.toLowerCase().startsWith("paid")) {
            sendMessageWithRetry(from, """
                    💰 Thank you! We've noted your payment. 🎉

                    An agent will confirm and process your order shortly.
                    You'll receive an update once your order is on its way! 🚚

                    Reply *Menu* to start a new order.
                    """);
            sessionService.setState(from, State.NONE);
            safeLog("Payment Confirmed", from, text, "-", "Payment Noted");

        } else if (text.equalsIgnoreCase("priority")) {
            sendMessageWithRetry(from, """
                    🚨 *Priority request noted!*

                    An agent has been alerted and will respond urgently.
                    ⏰ Expected response: within 30 minutes.
                    """);
            safeLog("Priority Request", from, text, "-", "Priority Escalated");
            alertAgentTeam(from, "PAYMENT STAGE");

        } else {
            sendMessageWithRetry(from, """
                    ⏳ We're waiting for your payment confirmation.

                    Once paid, reply *Paid* followed by your reference number.
                    Example: "Paid REF123456"

                    Or reply *Menu* to cancel and start over.
                    """);
        }
    }

    // ============ AGENT FLOW ============

    private void handleTalkingToAgent(String from, String text) {
        if (isBackCommand(text)) {
            sessionService.setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        if (text.equalsIgnoreCase("priority")) {
            sendMessageWithRetry(from, """
                    🚨 *Priority request noted!*

                    An agent has been alerted and will respond to you urgently.
                    ⏰ Expected response: within 30 minutes.

                    Reply *Menu* to go back to the main menu.
                    """);
            safeLog("Priority Request", from, text, "-", "Priority Escalated");
            alertAgentTeam(from, "AGENT CHAT");
            return;
        }
        sendMessageWithRetry(from, """
                ✅ Message received — an agent will respond shortly.

                Reply *Priority* for urgent help.
                Reply *Menu* to go back.
                """);
        safeLog("Agent Message", from, text, "-", "Awaiting Agent");
    }

    // ============ MESSAGE TEMPLATES ============

    private void sendMainMenu(String to) {
        sendMessageWithRetry(to, """
                👋🏾 Hi there — welcome to *Buyza!*

                We help you buy from major retailers in South Africa and deliver to Zimbabwe. 🇿🇦➡️🇿🇼

                Please reply with a number to get started:
                1️⃣ Online Order – You have a cart/product link ready
                2️⃣ Assisted Order – You want help finding products
                3️⃣ Delivery Info – Locations, costs & timelines
                4️⃣ Talk to an Agent – Chat with our team 👩🏾‍💻

                💬 Reply *Menu* anytime to return here.
                """);
    }

    private void sendOnlineOrderPrompt(String to) {
        sendMessageWithRetry(to, """
                🛒 *Online Order* selected ✅

                Please send us:
                • The link to your cart or product(s)
                • Total value of goods (in Rands)

                Example:
                Cart link: https://www.takealot.com/...
                Total: R850

                (Reply *Back* to go back or *Menu* to start over)
                """);
    }

    private void sendAssistedOrderPrompt(String to) {
        sendMessageWithRetry(to, """
                🛍️ *Assisted Order* selected ✅

                Please tell us:
                • Item name or description
                • Your budget
                • Any preferences (brand, colour, model, year)

                Example:
                "I need a replacement gearbox for a 2019 Mercedes-Benz GLC, budget R8 000"

                (Reply *Back* to go back or *Menu* to start over)
                """);
    }

    private void sendDeliveryInfo(String to) {
        sendMessageWithRetry(to, """
                🚚 *Delivery Information*

                • Orders delivered weekly or bi-weekly to Zimbabwe.
                • Delivery points: Harare, Bulawayo, Gweru, Mutare, Masvingo, Chinhoyi.
                • Door-to-door available in some areas (extra charge applies).
                • Delivery time: 3–7 working days.

                Reply *Menu* to go back or choose another option.
                """);
    }

    private void sendTalkToAgent(String to) {
        sendMessageWithRetry(to, """
                👩🏾‍💻 *Talk to an Agent*

                ✅ An agent will assist you as soon as possible.
                ⏰ Typical response: within a few hours.

                Reply *Priority* if you need urgent assistance.
                Reply *Back* to go back or *Menu* for all options.
                """);
    }

    private void sendFallback(String to) {
        sendMessageWithRetry(to, """
                ❓ Sorry, I didn't quite understand that.

                Please reply with a number:
                1️⃣ Online Order
                2️⃣ Assisted Order
                3️⃣ Delivery Info
                4️⃣ Talk to an Agent

                Or reply *Menu* to see the full menu.
                """);
    }

    // ============ SCHEDULED CLEANUP ============

    @Scheduled(fixedRate = 300000)
    public void cleanupProcessedMessages() {
        long now   = Instant.now().getEpochSecond();
        int before = processedMessages.size();
        processedMessages.entrySet().removeIf(e ->
                now - e.getValue() > MESSAGE_DEDUP_WINDOW_SECS);
        log.info("Message dedup cleanup: removed {} entries",
                before - processedMessages.size());
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupRateLimitMap() {
        long now = Instant.now().getEpochSecond();
        rateLimitMap.forEach((key, timestamps) ->
                timestamps.removeIf(t -> now - t > 60));
        rateLimitMap.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // ============ DEDUPLICATION ============

    private boolean isDuplicate(String messageId) {
        long now = Instant.now().getEpochSecond();
        if (processedMessages.containsKey(messageId)) return true;
        processedMessages.put(messageId, now);
        return false;
    }

    // ============ RATE LIMITING ============

    private boolean isRateLimited(String from) {
        long now = Instant.now().getEpochSecond();
        rateLimitMap.putIfAbsent(from, new ArrayList<>());
        List<Long> timestamps = rateLimitMap.get(from);
        timestamps.removeIf(t -> now - t > 60);
        if (timestamps.size() >= MAX_MESSAGES_PER_MINUTE) return true;
        timestamps.add(now);
        return false;
    }

    // ============ AGENT ALERT ============

    private void alertAgentTeam(String from, String context) {
        log.warn("🚨 PRIORITY REQUEST from +{} at stage: {}", from, context);
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) return;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> payload = Map.of(
                    "text", String.format(
                            "🚨 *PRIORITY REQUEST*\nFrom: +%s\nStage: %s\nRespond urgently!",
                            from, context)
            );
            restTemplate.postForEntity(slackWebhookUrl,
                    new HttpEntity<>(payload, headers), String.class);
            log.info("Slack alert sent for {}", from);
        } catch (Exception e) {
            log.error("Failed to send Slack alert: {}", e.getMessage());
        }
    }

    // ============ HELPERS ============

    private boolean isGreeting(String text) {
        if (text == null) return false;
        String lower = text.trim().toLowerCase();
        return lower.equals("hi")            || lower.equals("hello")         ||
                lower.equals("hey")           || lower.equals("start")         ||
                lower.equals("hie")           || lower.equals("howzit")        ||
                lower.equals("good morning")  || lower.equals("good afternoon")||
                lower.equals("good evening");
    }

    private boolean isBackCommand(String text) {
        if (text == null) return false;
        String lower = text.trim().toLowerCase();
        return lower.equals("back") || lower.equals("cancel") || lower.equals("0");
    }

    private void safeLog(String type, String phone, String message,
                         String quote, String status) {
        try {
            String truncated = message != null && message.length() > MAX_LOG_MESSAGE_LENGTH
                    ? message.substring(0, MAX_LOG_MESSAGE_LENGTH - 3) + "..."
                    : message;
            sheetsService.saveInteraction(type, phone, truncated, quote, status);
        } catch (Exception e) {
            log.warn("⚠️ Failed to save to sheets: {} | type={}, phone={}",
                    e.getMessage(), type, phone);
        }
    }

    // ============ SEND HELPERS ============

    private void sendMessageWithRetry(String to, String body) {
        int tries = 0, max = 3;
        while (tries < max) {
            try {
                sendMessage(to, body);
                return;
            } catch (RuntimeException e) {
                tries++;
                log.warn("Send attempt #{} failed for {}: {}", tries, to, e.getMessage());
                try {
                    Thread.sleep(400L * tries);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.error("Failed to send message to {} after {} tries", to, max);
    }

    private void sendMessage(String to, String body) {
        if (Objects.isNull(phoneNumberId) || phoneNumberId.isBlank())
            throw new IllegalStateException("phoneNumberId not configured");
        if (Objects.isNull(accessToken) || accessToken.isBlank())
            throw new IllegalStateException("accessToken not configured");

        String url = String.format(
                "https://graph.facebook.com/v22.0/%s/messages", phoneNumberId);

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
            log.error("WhatsApp API error: {} -> {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("WhatsApp API error " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Error sending message: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}