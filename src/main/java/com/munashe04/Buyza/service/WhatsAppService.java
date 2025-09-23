package com.munashe04.Buyza.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    private final RestTemplate restTemplate;
    private final GoogleSheetsService googleSheetsService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    public WhatsAppService(RestTemplate restTemplate, GoogleSheetsService googleSheetsService) {
        this.restTemplate = restTemplate;
        this.googleSheetsService = googleSheetsService;
    }

    /**
     * Main conversational flow - matches your spec
     */
    public void handleIncomingMessage(String from, String rawText) {
        String text = rawText == null ? "" : rawText.trim();

        // NEW: Check if this is a returning customer with active orders
        if (isReturningCustomerGreeting(text)) {
            handleReturningCustomer(from, text);
            return;
        }

        // Greeting
        if (text.equalsIgnoreCase("hi") || text.equalsIgnoreCase("hello") || text.equalsIgnoreCase("start")) {
            String greeting = """
                    👋🏾 Hi there! Welcome to Buyza – your trusted shopping assistant.
                    We help you buy from major retailers in South Africa and deliver to Zim.
                                        
                    Please reply with a number to get started:
                    1️⃣ Online Order – You already know what you want and have a cart/product link (10% service fee, plus delivery)
                    2️⃣ Assisted Order – You want help choosing or finding products (20% service fee, plus delivery)
                    3️⃣ Delivery Info – View delivery locations, costs & timelines
                    4️⃣ Talk to an Agent – Chat directly with a team member 👩🏾‍💻
                    """;
            sendMessage(from, greeting);
            googleSheetsService.saveInteraction("Greeting",from, text, "-", "Active");
            return;
        }

        // Options
        switch (text) {
            case "1":
                sendMessage(from, """
                        💬 Awesome! You've selected Online Order ✅
                        Please send:
                        ○ 🛒 The link to your cart or product(s)
                        ○ 💰 Total value of goods
                        📌 Example:
                        Cart link: [Takealot cart]
                        Total: R850
                        """);
                googleSheetsService.saveInteraction("Online Order Start", from, text, "-", "New Order");
                return;

            case "2":
                sendMessage(from, """
                        👋🏾 No problem! You've selected Assisted Order ✅
                        Please tell us what you’re looking for:
                        ○ Item name or description
                        ○ Budget
                        ○ Any preferences (e.g. colour, brand, must-have features)
                        """);
                googleSheetsService.saveInteraction("Assisted Order Start", from, text, "-", "New Order");
                return;

            case "3":
                sendMessage(from, """
                        📦 Here’s how delivery works:
                        Orders are delivered weekly or bi-weekly to Zimbabwe
                        Delivery points: Harare, Bulawayo, Gweru, Mutare, Masvingo, Chinhoyi
                        Door-to-door delivery available in some areas for extra charge
                        📄 View our delivery cost & timeline sheet here: [Google Sheet Link]
                        """);
                googleSheetsService.saveInteraction(from, "Delivery Inquiry", text, "-", "Info Provided");
                return;

            case "4":
                sendMessage(from, """
                        ✅ Thank you for reaching out! An agent will assist you as soon as possible.
                        ⏰ Our response time is usually within a few hours, but it may take longer depending on demand. We appreciate your patience 🙏🏾.
                        """);
                googleSheetsService.saveInteraction(from, "Agent Request", text, "-", "Escalated");
                return;

            default:
                // Online order details (user provides cart link & total)
                if (looksLikeCartSubmission(text)) {
                    sendMessage(from, "✅ Thank you! An agent will get back to you with your final quote.");
                    googleSheetsService.saveInteraction("Online Order Details", from, text, "-", "Details Provided");
                    return;
                }

                // Assisted order request detection
                if (looksLikeAssistedRequest(text)) {
                    sendMessage(from, "✅ Thank you! Our team will be looking into your request. An agent will get back to you within 3 working days with a quote.");
                    googleSheetsService.saveInteraction("Assisted Order Details", from, text, "-", "Details Provided");
                    return;
                }

                // Quote confirmation (Yes/No)
                if (text.equalsIgnoreCase("yes")) {
                    sendMessage(from, "Perfect! ✅ A payment link will be sent to you shortly.");
                    googleSheetsService.saveInteraction(from, "Quote Accepted", text, "-", "Payment Pending");
                    return;
                } else if (text.equalsIgnoreCase("no")) {
                    sendMessage(from, "No worries 👌🏾. Your request has been cancelled. You’re welcome to shop with us anytime!");
                    googleSheetsService.saveInteraction(from, "Quote Rejected", text, "-", "Cancelled");
                    return;
                }

                // Fallback: prompt menu
                sendMessage(from, "Sorry, I didn’t understand. Please reply with:\n1️⃣ Online Order\n2️⃣ Assisted Order\n3️⃣ Delivery Info\n4️⃣ Talk to an Agent");
                return;
        }
    }

    private void handleReturningCustomer(String from, String text) {
        // For now, we'll let the GoogleSheetsService decide whether to create new order or continue existing
        // The service will detect active orders and handle accordingly

        if (text.equalsIgnoreCase("hi") || text.equalsIgnoreCase("hello")) {
            String greeting = """
                👋🏾 Welcome back! 
                
                You're already in our system. Would you like to:
                1️⃣ Continue with your current order
                2️⃣ Start a new order
                3️⃣ Check order status
                4️⃣ Talk to an agent
                """;
            sendMessage(from, greeting);
            googleSheetsService.saveInteraction("Returning Customer Greeting", from, text, "-", "Active Session");
        } else {
            // Let the service handle based on order detection logic
            googleSheetsService.saveInteraction("Returning Customer Message", from, text, "-", "Continuing Session");
        }
    }

    /**
     * NEW: Detect if this is a greeting from a returning customer
     */
    private boolean isReturningCustomerGreeting(String text) {
        if (text == null) return false;
        String lowerText = text.toLowerCase();
        return lowerText.equals("hi") ||
                lowerText.equals("hello") ||
                lowerText.equals("hey") ||
                lowerText.equals("back") ||
                lowerText.contains("order") ||
                lowerText.contains("status");
    }

    private boolean looksLikeCartSubmission(String text) {
        String low = text.toLowerCase();
        return low.contains("cart link:") || (low.contains("total:") && (low.contains("http") || low.contains("takealot") || low.contains("cart")));
    }

    private boolean looksLikeAssistedRequest(String text) {
        String low = text.toLowerCase();
        return low.contains("need") || low.contains("please") || low.contains("help") || low.contains("budget") || low.contains("looking for");
    }

    /**
     * Sends a text message via WhatsApp Cloud API (includes messaging_product)
     */
  /*  public void sendMessage(String to, String body) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            log.error("phoneNumberId not configured - cannot send message");
            return;
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.error("accessToken not configured - cannot send message");
            return;
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

        // basic retry for transient 5xx
        int tries = 0;
        while (tries < 3) {
            try {
                ResponseEntity<String> resp = restTemplate.postForEntity(url, req, String.class);
                log.debug("WhatsApp send response: status={} body={}", resp.getStatusCodeValue(), resp.getBody());
                return;
            } catch (HttpClientErrorException e) {
                // log 4xx with body
                log.error("Error sending WhatsApp message (client error): {} -> {}", e.getStatusCode(), e.getResponseBodyAsString());
                return;
            } catch (Exception e) {
                tries++;
                log.warn("Transient error sending WhatsApp message (attempt {}): {}", tries, e.getMessage());
                try { Thread.sleep(1000L * tries); } catch (InterruptedException ignored) {}
            }
        }
        log.error("Failed to send WhatsApp message after retries");
    }

   */




    public void sendMessage(String to, String body) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            log.error("phoneNumberId not configured - cannot send message");
            return;
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.error("accessToken not configured - cannot send message");
            return;
        }

        log.info("📤 Sending WhatsApp message to: {}", to);

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
            log.info("✅ WhatsApp message sent successfully. Status: {}", resp.getStatusCode());
        } catch (HttpClientErrorException e) {
            log.error("❌ WhatsApp API error: {} -> {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ Error sending WhatsApp message: {}", e.getMessage());
        }
    }

    // Add this method to your WhatsAppService class
    private void sendMessageWithRetry(String to, String body) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                sendMessage(to, body);
                return;
            } catch (Exception e) {
                retryCount++;
                log.warn("Attempt {} failed to send message to {}: {}", retryCount, to, e.getMessage());

                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(1000 * retryCount); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("Failed to send message to {} after {} attempts", to, maxRetries);
    }
}
