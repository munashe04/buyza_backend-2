package com.munashe04.Buyza.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // 🔹 Main entry point
    public void handleIncomingMessage(String from, String rawText) {
        String text = rawText == null ? "" : rawText.trim();

        if (text.equalsIgnoreCase("hi") || text.equalsIgnoreCase("hello") || text.equalsIgnoreCase("start")) {
            sendMainMenu(from);
            sheetsService.saveInteraction("Greeting", from, text, "-", "Active");
            return;
        }

        switch (text) {
            case "1":
                sendMessage(from, """
                        🛒 *Online Order* selected!  

                        Please send us:  
                        1️⃣ Cart or product link   
                        2️⃣ Total value of goods  💰

                        Example:  
                        Cart link: https://www.takealot.com/cart/123  
                        Total: R850  
                        """);
                sheetsService.saveInteraction("Online Order Start", from, text, "-", "New Order");
                return;

            case "2":
                sendMessage(from, """
                        🛍️ *Assisted Order* selected!  

                        Please share details so we can help you shop:  
                        • Item name or description  
                        • Your budget  
                        • Any preferences (brand, colour, size, features)  

                        📌 Kindly include your Budget in Rands, eg: White nike shoes, R2500
                        """);
                sheetsService.saveInteraction("Assisted Order Start", from, text, "-", "New Order");
                return;

            case "3":
                sendMessage(from, """
                        🚚 *Delivery Information*  

                        • Weekly / bi-weekly deliveries to 🇿🇼 Zimbabwe  
                        • Pickup points: Harare, Bulawayo, Gweru, Mutare, Masvingo, Chinhoyi  
                        • 🚪 Door-to-door delivery available at an extra charge  

                        📑 See full delivery cost & timeline here: [Google Sheet Link]  
                        """);
                sheetsService.saveInteraction("Delivery Info", from, text, "-", "Info Provided");
                return;

            case "4":
                sendMessage(from, """
                        👩🏾‍💻 *Talk to an Agent*  

                        ✅ Your request has been escalated.  
                        ⏰ Response time is usually a few hours (may be longer during busy times).  

                        Thank you for your patience! 🙏🏾  
                        """);
                sheetsService.saveInteraction("Agent Request", from, text, "-", "Escalated");
                return;

            default:
                handleFreeText(from, text);
        }
    }

    // 🔹 Handle free text input
    private void handleFreeText(String from, String text) {
        if (looksLikeCartSubmission(text)) {
            BigDecimal total = extractAmount(text);
            if (total != null) {
                BigDecimal serviceFee = total.multiply(BigDecimal.valueOf(0.10)).setScale(2, BigDecimal.ROUND_HALF_UP);
                BigDecimal finalAmount = total .add(serviceFee);

                String summary = String.format("""
                        ✅ *Order Summary*  

                        Goods Total: R%.2f  
                        Service Fee (10%%): R%.2f  
                        ----------------------  
                        *Total Payable: R%.2f*  

                        Please confirm to proceed:  
                        Reply *Yes* to continue, or *No* to cancel.  
                        """, total, serviceFee, finalAmount);

                sendMessage(from, summary);
                sheetsService.saveInteraction("Online Order Details", from, text, "-", "Summary Sent");
            } else {
                sendMessage(from, "⚠️ Couldn’t detect total amount. Please resend including the word 'Total: Rxxx'.");
            }
            return;
        }

        // if (looksLikeAssistedRequest(text)) {
        BigDecimal budget = extractAmount(text);
        if (budget != null) {
            BigDecimal serviceFee = budget.multiply(BigDecimal.valueOf(0.10)).setScale(2, BigDecimal.ROUND_HALF_UP);
            BigDecimal finalAmount = budget .add(serviceFee);

            String summary = String.format("""
                    🙌 Thank you!  

                    Based on your request, here’s a *provisional estimate*:  

                    Budget: R%.2f  
                    Service Fee (20%%): R%.2f  
                    ----------------------  
                    *Estimated Total: R%.2f*  

                    Please confirm to proceed:  
                    Reply *Yes* to continue, or *No* to cancel.  
                    """, budget, serviceFee, finalAmount);

            sendMessage(from, summary);
            sheetsService.saveInteraction("Assisted Order Details", from, text, "-", "Summary Sent");
        }
            /*else {
                sendMessage(from, "✅ Got it! Our team will search for your item. You’ll get a tailored quote soon.");
            }
            return;
            }
             */
        if (!text.isBlank()) {
            if (text.equalsIgnoreCase("yes")) {
                sendMessage(from, """
                        We’ll send you a secure payment link shortly.  
                        """);
                sheetsService.saveInteraction("Order Confirmed", from, text, "-", "Payment Pending");
                return;
            } else if (text.equalsIgnoreCase("no")) {
                sendMessage(from, """
                        👌 No problem.  

                        Your request has been cancelled.  
                        You’re welcome anytime!  
                        """);
                sheetsService.saveInteraction("Order Cancelled", from, text, "-", "Cancelled");
                return;
            }
        }
       else sendMessage(from, """
                    ❓ Sorry, I didn’t understand that.  

                    Please choose an option:  
                    1️⃣ Online Order  
                    2️⃣ Assisted Order  
                    3️⃣ Delivery Info  
                    4️⃣ Talk to an Agent  
                    """);
    }

    //
  /*  private Double extractAmount(String text) {
        Pattern pattern = Pattern.compile("([rR]?(\\d+)(\\.\\d{1,2})?)");
        Matcher matcher = pattern.matcher(text.replace(",", ""));
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(2));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

   */

    private BigDecimal extractAmount(String text) {
        // naive: find "r" or "R" followed by numbers
        String low = text.replaceAll(",", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[Rr]\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(low);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (Exception e) { }
        }
        // try digits only
        m = java.util.regex.Pattern.compile("(?:total[:\\s]*)([0-9]+(?:\\.[0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            try { return new BigDecimal(m.group(1)); } catch (Exception e) {}
        }
        return null;
    }

    // 🔹 Send main menu
    private void sendMainMenu(String to) {
        String menu = """
                👋🏾 *Welcome to Buyza!*  

                Your trusted 🇿🇦 SA → 🇿🇼 Zim shopping assistant.  

                Please reply with a number:  
                1️⃣ *Online Order* – Already have a cart or product link (10% service fee + delivery)  
                2️⃣ *Assisted Order* – Need help finding items (20% service fee + delivery)  
                3️⃣ *Delivery Info* – View delivery points, costs & timelines  
                4️⃣ *Talk to an Agent* – Chat with our support team 👩🏾‍💻  
                """;
        sendMessage(to, menu);
    }

    // 🔹 Utility detectors
    private boolean looksLikeCartSubmission(String text) {
        String low = text.toLowerCase();
        return low.contains("total:") || low.contains("cart link") || low.contains("link") || low.contains("http");
    }

    private boolean looksLikeAssistedRequest(String text) {
        String low = text.toLowerCase();
        return low.contains("looking for") || low.contains("need") || low.contains("want") ||
                low.contains("budget") || low.contains("brand") || low.contains("size") || low.contains("buy") || low.contains("help");
    }

    // 🔹 Send WhatsApp message
    public void sendMessage(String to, String body) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            log.error("⚠️ phoneNumberId not configured - cannot send message");
            return;
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.error("⚠️ accessToken not configured - cannot send message");
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
            log.info("✅ WhatsApp message sent. Status: {}", resp.getStatusCode());
        } catch (HttpClientErrorException e) {
            log.error("❌ WhatsApp API error: {} -> {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ Error sending WhatsApp message: {}", e.getMessage());
        }
    }
}
