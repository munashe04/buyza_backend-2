package com.munashe04.Buyza.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
    private static final long SESSION_TIMEOUT_SECONDS = 3600; // 1 hour

    public enum State {
        NONE,
        AWAITING_ONLINE_ORDER_DETAILS,
        AWAITING_ASSISTED_ORDER_DETAILS,
        AWAITING_QUOTE_CONFIRMATION,
        AWAITING_PAYMENT
    }

    // Session entry with timestamp for timeout
    private record Session(State state, Instant updatedAt) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> processedMessages = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate;
    private final GoogleSheetsService sheetsService;
    private final FaqService faqService;

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.token}")
    private String accessToken;

    public WhatsAppService(RestTemplate restTemplate,
                           GoogleSheetsService sheetsService,
                           FaqService faqService) {
        this.restTemplate = restTemplate;
        this.sheetsService = sheetsService;
        this.faqService = faqService;
    }

    // ============ ENTRY POINT ============

    public void handleIncomingMessage(String from, String messageId, String type, String rawText) {

        // 1. Deduplicate — ignore if already processed
        if (processedMessages.containsKey(messageId)) {
            log.info("Duplicate message {} from {} — ignoring", messageId, from);
            return;
        }
        processedMessages.put(messageId, from);

        // 2. Handle non-text message types
        if (!type.equals("text")) {
            handleNonTextMessage(from, type);
            return;
        }

        String text = rawText == null ? "" : rawText.trim();
        State currentState = getState(from);

        log.info("User {} | State: {} | Message: {}", from, currentState, text);

        // 3. Always allow menu reset
        if (text.equalsIgnoreCase("menu") || text.equals("0")) {
            setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }

        // 4. Route based on state
        switch (currentState) {
            case AWAITING_ONLINE_ORDER_DETAILS  -> handleOnlineOrderDetails(from, text);
            case AWAITING_ASSISTED_ORDER_DETAILS -> handleAssistedOrderDetails(from, text);
            case AWAITING_QUOTE_CONFIRMATION     -> handleQuoteConfirmation(from, text);
            case AWAITING_PAYMENT                -> handlePaymentConfirmation(from, text);
            default                              -> handleMenuSelection(from, text);
        }
    }

    // ============ NON-TEXT HANDLER ============

    private void handleNonTextMessage(String from, String type) {
        String response = switch (type) {
            case "image", "document", "video" ->
                    "📎 Thanks for sending that! Unfortunately our bot can't process files yet.\n\nReply *4* to talk to an agent who can help, or reply *Menu* to see options.";
            case "audio", "voice" ->
                    "🎙️ Voice notes aren't supported yet.\n\nPlease type your message or reply *Menu* to see options.";
            case "sticker", "reaction" ->
                    "😊 Thanks! Reply *Menu* to see what we can help you with.";
            case "location" ->
                    "📍 Thanks for sharing your location! Reply *3* for delivery info or *Menu* for options.";
            default ->
                    "Sorry, I can't process that type of message. Reply *Menu* to see options.";
        };
        sendMessageWithRetry(from, response);
    }

    // ============ STATE HANDLERS ============

    private void handleMenuSelection(String from, String text) {
        if (isGreeting(text)) {
            sendMainMenu(from);
            safeLog("Greeting", from, text, "-", "Active");
            return;
        }

        switch (text) {
            case "1" -> {
                setState(from, State.AWAITING_ONLINE_ORDER_DETAILS);
                sendOnlineOrderPrompt(from);
                safeLog("Online Order Start", from, text, "-", "New Order");
            }
            case "2" -> {
                setState(from, State.AWAITING_ASSISTED_ORDER_DETAILS);
                sendAssistedOrderPrompt(from);
                safeLog("Assisted Order Start", from, text, "-", "New Order");
            }
            case "3" -> {
                sendDeliveryInfo(from);
                safeLog("Delivery Info", from, text, "-", "Info Provided");
            }
            case "4" -> {
                sendTalkToAgent(from);
                safeLog("Agent Request", from, text, "-", "Escalated");
            }
            default -> {
                if (faqService.isFaqQuestion(text)) {
                    sendMessageWithRetry(from, faqService.answer(text));
                } else {
                    sendFallback(from);
                }
            }
        }
    }

    private void handleOnlineOrderDetails(String from, String text) {
        if (isBackCommand(text)) {
            setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        sendMessageWithRetry(from, """
                ✅ Thank you! We've received your cart details.

                An agent will get back to you with your final quote shortly.

                Would you like to proceed with this order once you receive the quote?
                Reply *Yes* to confirm or *No* to cancel.
                (Reply *Menu* anytime to start over)
                """);
        setState(from, State.AWAITING_QUOTE_CONFIRMATION);
        safeLog("Online Order Details", from, text, "-", "Details Provided");
    }

    private void handleAssistedOrderDetails(String from, String text) {
        if (isBackCommand(text)) {
            setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        sendMessageWithRetry(from, """
                ✅ Thanks — we received your request.

                Our team will review and reply with a quote within 3 working days.

                Would you like to proceed once you receive the quote?
                Reply *Yes* to confirm or *No* to cancel.
                (Reply *Menu* anytime to start over)
                """);
        setState(from, State.AWAITING_QUOTE_CONFIRMATION);
        safeLog("Assisted Order Details", from, text, "-", "Details Provided");
    }

    private void handleQuoteConfirmation(String from, String text) {
        if (isBackCommand(text)) {
            setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        if (text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("y")) {
            sendMessageWithRetry(from, """
                    ✅ Perfect! A payment link will be sent to you shortly.

                    Once you receive it, complete payment and reply *Paid* followed by your reference number.
                    Example: "Paid REF123456"
                    """);
            setState(from, State.AWAITING_PAYMENT);
            safeLog("Quote Accepted", from, text, "-", "Awaiting Payment");

        } else if (text.equalsIgnoreCase("no") || text.equalsIgnoreCase("n")) {
            sendMessageWithRetry(from, """
                    👌 No problem — your request has been cancelled.

                    Reply *Menu* to start a new order anytime.
                    """);
            setState(from, State.NONE);
            safeLog("Quote Rejected", from, text, "-", "Cancelled");

        } else {
            sendMessageWithRetry(from,
                    "Please reply *Yes* to confirm or *No* to cancel.\n(Reply *Menu* to start over)");
        }
    }

    private void handlePaymentConfirmation(String from, String text) {
        if (isBackCommand(text)) {
            setState(from, State.NONE);
            sendMainMenu(from);
            return;
        }
        if (text.toLowerCase().startsWith("paid")) {
            sendMessageWithRetry(from, """
                    💰 Thank you! We've noted your payment.

                    An agent will confirm and process your order shortly. 🎉

                    Reply *Menu* to start a new order.
                    """);
            setState(from, State.NONE);
            safeLog("Payment Confirmed", from, text, "-", "Payment Noted");
        } else if (text.equalsIgnoreCase("priority")) {
            sendMessageWithRetry(from, """
                    🚨 Priority flag noted! An agent will attend to you urgently.
                    """);
            safeLog("Priority Request", from, text, "-", "Priority");
        } else {
            sendMessageWithRetry(from, """
                    ⏳ We're waiting for your payment confirmation.

                    Once paid, reply *Paid* followed by your reference number.
                    Example: "Paid REF123456"

                    Or reply *Menu* to cancel and start over.
                    """);
        }
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

                (Reply *Menu* to go back)
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

                (Reply *Menu* to go back)
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
                Reply *Menu* to go back.
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

    // ============ SESSION MANAGEMENT ============

    private State getState(String from) {
        Session session = sessions.get(from);
        if (session == null) return State.NONE;

        // Check for timeout
        long secondsElapsed = Instant.now().getEpochSecond() - session.updatedAt().getEpochSecond();
        if (secondsElapsed > SESSION_TIMEOUT_SECONDS) {
            log.info("Session expired for {}", from);
            sessions.remove(from);
            return State.NONE;
        }
        return session.state();
    }

    private void setState(String from, State state) {
        sessions.put(from, new Session(state, Instant.now()));
    }

    // ============ HELPERS ============

    private boolean isGreeting(String text) {
        if (text == null) return false;
        String lower = text.trim().toLowerCase();
        return lower.equals("hi") || lower.equals("hello") || lower.equals("hey") ||
                lower.equals("start") || lower.equals("hie") || lower.equals("howzit") ||
                lower.equals("good morning") || lower.equals("good afternoon");
    }

    private boolean isBackCommand(String text) {
        if (text == null) return false;
        String lower = text.trim().toLowerCase();
        return lower.equals("back") || lower.equals("cancel") || lower.equals("0");
    }

    private void safeLog(String type, String phone, String message, String quote, String status) {
        try {
            sheetsService.saveInteraction(type, phone, message, quote, status);
        } catch (Exception e) {
            log.warn("Failed to save to sheets: {}", e.getMessage());
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
                try { Thread.sleep(400L * tries); } catch (InterruptedException ignored) {
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

        String url = String.format("https://graph.facebook.com/v22.0/%s/messages", phoneNumberId);
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