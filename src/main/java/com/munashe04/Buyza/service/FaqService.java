package com.munashe04.Buyza.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class FaqService {

    private final Map<String,String> faqMap = Map.of(
            "what stores can i shop from", "Most South African stores (Takealot, PnP, Checkers, Mr Price, etc.)",
            "how do i pay", "Pay via EcoCash, Mukuru, or bank transfer (ZWL or USD depending on option).",
            "how long", "Delivery usually 3–7 working days depending on your location.",
            "what can i buy", "Groceries, clothes, tech — anything legal and shippable."
    );

    public boolean isFaqQuestion(String text) {
        if (text == null) return false;
        String key = text.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
        return faqMap.keySet().stream().anyMatch(key::contains);
    }

    public String answer(String text) {
        if (text == null) return defaultFaqs();
        String key = text.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
        return faqMap.entrySet().stream()
                .filter(e -> key.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(defaultFaqs());
    }

    public String defaultFaqs() {
        return "💬 Buyza FAQs\n\n" +
                "Q: Stores? A: Takealot, PnP, Checkers, Mr Price, etc.\n" +
                "Q: How to pay? A: EcoCash, Mukuru, Bank transfer.\n" +
                "Q: Delivery time? A: 3–7 working days.\n" +
                "Reply 'Chat with Assistant' to speak to someone.";
    }
}
