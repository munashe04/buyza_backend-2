package com.munashe04.Buyza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.munashe04.Buyza.moddel.Order;
import com.munashe04.Buyza.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
public class FlowService {

    private final WhatsAppService wa;
    private final OrderRepository orderRepo;
    private final FaqService faqService;
    private final DeliveryService deliveryService;
    private final PaymentService paymentService;

    public FlowService(WhatsAppService wa, OrderRepository orderRepo,
                       FaqService faqService, DeliveryService deliveryService,
                       PaymentService paymentService) {
        this.wa = wa;
        this.orderRepo = orderRepo;
        this.faqService = faqService;
        this.deliveryService = deliveryService;
        this.paymentService = paymentService;
    }

    /**
     * Parse the incoming JSON and route to a handler
     */
    public void handleIncoming(JsonNode root) {
        // Typical path: entry[0].changes[0].value.messages[0]
        try {
            JsonNode messages = root.path("entry").get(0)
                    .path("changes").get(0)
                    .path("value").path("messages");

            if (messages.isMissingNode() || !messages.isArray() || messages.size() == 0) {
                // Could be statuses or other event -> ignore for now
                return;
            }

            JsonNode message = messages.get(0);
            String from = message.path("from").asText(); // sender phone
            String text = extractTextFromMessage(message);

            if (text == null || text.isBlank()) {
                wa.sendMessage(from, "Sorry, I didn't understand that. Reply 1, 2, 3, or 4.");
                return;
            }

            text = text.trim();

            // If user previously started order - very simple state: if last order AWAITING_PAYMENT, treat replies as payment confirmations etc.
            // For MVP, we'll use very simple parsing:
            switch (text) {
                case "1", "1️⃣", "online", "online order" -> startOnlineOrder(from);
                case "2", "2️⃣", "assisted", "assisted order" -> startAssistedOrder(from);
                case "3", "3️⃣", "delivery" -> sendDeliveryInfo(from);
                case "4", "4️⃣", "faqs", "faq" -> sendFaqs(from);
                case "chat with assistant", "chat", "chat with agent" -> wa.sendMessage(from, "Connecting you to an assistant. Please wait...");
                default -> {
                    // Recognize if user is sending order details by a simple pattern: contains 'cart' or 'total' or 'delivery:' etc.
                    if (looksLikeOrder(detailsFrom(text))) {
                        handleOnlineOrderDetails(from, text);
                    } else if (text.toLowerCase().startsWith("track")) {
                        // e.g., "track 123"
                        String id = text.split("\\s+", 2).length > 1 ? text.split("\\s+", 2)[1] : null;
                        sendTrackingInfo(from, id);
                    } else if (faqService.isFaqQuestion(text)) {
                        wa.sendMessage(from, faqService.answer(text));
                    } else if (paymentService.isPaymentConfirmation(text)) {
                        handlePaymentConfirmation(from, text);
                    } else {
                        wa.sendMessage(from, "👋🏾Hi there! Welcome to Buyza - your trusted shopping assistant." +
                                "We help you buy from major retailers in South Africa and deliver it to you in Zim.\n" +
                                "Please reply with a number to get started:" +
                                "\nReply with a number to get started:" +
                                "\n1️⃣ Online Order – You already know what you want and have a cart/product link (10% service fee, plus delivery)" +
                                "\n2️⃣ Assisted Order - You want help choosing or finding products (20% service fee, plus delivery)" +
                                "\n3️⃣ Delivery Info - View delivery locations, costs & timelines" +
                                "\n4️⃣ FAQs" +
                                "\nOr reply 'Chat with Assistant' to talk to an agent.");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String extractTextFromMessage(JsonNode message) {
        // message.text.body OR message.interactive?.button_reply?.id/text
        if (message.has("text") && message.path("text").has("body")) {
            return message.path("text").path("body").asText();
        }
        if (message.has("interactive")) {
            JsonNode interactive = message.path("interactive");
            if (interactive.has("button_reply")) {
                return interactive.path("button_reply").path("title").asText();
            }
            if (interactive.has("list_reply")) {
                return interactive.path("list_reply").path("title").asText();
            }
        }
        return null;
    }

    private void startOnlineOrder(String from) {
        String msg = "💬 Awesome! You've selected *Online Order* ✅\n" +
                "\nPlease send:" +
                "\n- Cart link(s)" +
                "\n- Total value (e.g. R850)" +
                "\n- Delivery town/city in Zimbabwe\n" +
                "\nExample:" +
                "\nCart link: [Takealot link]" +
                "\nTotal: R850" +
                "\nDelivery: Gweru";
        wa.sendMessage(from, msg);
    }

    private void startAssistedOrder(String from) {
        String msg = "👋🏾 You’ve selected *Assisted Order* ✅\n" +
                "\nTell us:" +
                "\n- Item name/description" +
                "\n- Budget" +
                "\n- Preferences (colour/brand/feature)\n" +
                "\nExample: \"I want a 3-piece cookware set, non-stick, budget R600\"";
        wa.sendMessage(from, msg);
    }

    private void sendDeliveryInfo(String from) {
        String msg = "📦 Buyza Delivery Info:" +
                "\nOrders are delivered weekly/bi-weekly to Zimbabwe." +
                "\nDelivery points: Harare, Bulawayo, Gweru, Mutare, Masvingo, Chinhoyi" +
                "\nDoor-to-door available in some areas (extra charge)." +
                "\nSee full delivery sheet: [Google Sheet Link]";
        wa.sendMessage(from, msg);
    }

    private void sendFaqs(String from) {
        wa.sendMessage(from, faqService.defaultFaqs());
    }

    private boolean looksLikeOrder(String text) {
        if (text == null) return false;
        String low = text.toLowerCase();
        return low.contains("cart") || low.contains("total") || low.contains("delivery");
    }

    private String detailsFrom(String text) {
        return text;
    }

    private void handleOnlineOrderDetails(String from, String text) {
        // Very simple parser to extract a total and delivery town
        // Expect formats like: "Cart link: ... Total: R850 Delivery: Gweru"
        BigDecimal total = extractTotal(text);
        String town = extractTown(text);

        if (total == null || town == null) {
            wa.sendMessage(from, "I couldn't parse your order. Please send: Cart link, Total (e.g. R850), Delivery town (e.g. Gweru).");
            return;
        }

        BigDecimal serviceFee = total.multiply(BigDecimal.valueOf(0.10)).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal deliveryFee = estimateDeliveryFee(town);
        BigDecimal subtotal = total.add(serviceFee).add(deliveryFee);

        Order order = Order.builder()
                .customerPhone(from)
                .details(text)
                .goodsValue(total)
                .serviceFee(serviceFee)
                .deliveryFee(deliveryFee)
                .subtotal(subtotal)
                .deliveryTown(town)
                .status(Order.Status.AWAITING_PAYMENT)
                .createdAt(OffsetDateTime.now())
                .build();
        orderRepo.save(order);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ Thanks! Here’s your quote:\n\n");
        sb.append("Goods: R").append(total).append("\n");
        sb.append("Service fee (10%): R").append(serviceFee).append("\n");
        sb.append("Delivery cost: R").append(deliveryFee).append("\n");
        sb.append("Subtotal: R").append(subtotal).append("\n\n");
        sb.append("Please confirm and proceed to payment (EcoCash / Mukuru / Bank).\n");
        sb.append("When you've paid, reply with the payment reference or 'Paid'.\n");
        sb.append("Order ID: ").append(order.getId());

        wa.sendMessage(from, sb.toString());
    }

    private BigDecimal extractTotal(String text) {
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

    private String extractTown(String text) {
        // naive: look for "delivery:" or last word
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("delivery\\s*[:\\-]?\\s*(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return capitalize(m.group(1));
        // fallback: none
        return null;
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0,1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private BigDecimal estimateDeliveryFee(String town) {
        // sample simple mapping — replace with your sheet lookup or DB
        switch (town.toLowerCase()) {
            case "harare": return BigDecimal.valueOf(150);
            case "bulawayo": return BigDecimal.valueOf(200);
            case "gweru": return BigDecimal.valueOf(180);
            default: return BigDecimal.valueOf(250);
        }
    }

    private void sendTrackingInfo(String from, String idStr) {
        if (idStr == null) {
            wa.sendMessage(from, "Please send 'track <orderId>' e.g., 'track 123'.");
            return;
        }
        try {
            Long id = Long.parseLong(idStr);
            orderRepo.findById(id).ifPresentOrElse(order -> {
                String reply = "Order ID: " + id + "\nStatus: " + order.getStatus();
                wa.sendMessage(from, reply);
            }, () -> wa.sendMessage(from, "Order not found with ID: " + id));
        } catch (NumberFormatException e) {
            wa.sendMessage(from, "Invalid order id. Use the numeric Order ID we provided.");
        }
    }

    private void handlePaymentConfirmation(String from, String text) {
        // Search for last AWAITING_PAYMENT order for this user
        Optional<Order> maybe = orderRepo.findByCustomerPhoneOrderByCreatedAtDesc(from).stream()
                .filter(o -> o.getStatus() == Order.Status.AWAITING_PAYMENT).findFirst();
        if (maybe.isEmpty()) {
            wa.sendMessage(from, "No pending order found. If you need help, reply 'Chat with Assistant'.");
            return;
        }
        Order order = maybe.get();
        // mark paid
        order.setStatus(Order.Status.PAID);
        orderRepo.save(order);

        wa.sendMessage(from, "🔄 Payment received! We’ll process your order and update you when it’s ready to ship.\nETA: 3–7 business days.");
        // proceed to process order (place order with SA merchant) -> stub:
        processOrder(order);
    }

    private void processOrder(Order order) {
        // STUB: do the actual procurement or hand over to human operator
        order.setStatus(Order.Status.PROCESSING);
        orderRepo.save(order);
        // Send a notification to admin/agents (not implemented here)
    }
}
