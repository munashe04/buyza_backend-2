package com.munashe04.Buyza.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class FaqService {

    private final Map<String, String> faqMap = Map.of(
            "what stores",     "We shop from most South African stores — Takealot, PnP, Checkers, Mr Price, Game, Builders, and more.",
            "how do i pay",    "Pay via EcoCash, Mukuru, or bank transfer (ZWL or USD depending on the option).",
            "how long",        "Delivery usually takes 3–7 working days depending on your location in Zimbabwe.",
            "what can i buy",  "Groceries, clothing, electronics, car parts — anything legal and shippable.",
            "delivery cost",   "Delivery costs depend on item size and weight. Reply *3* for full delivery info.",
            "where deliver",   "We deliver to Harare, Bulawayo, Gweru, Mutare, Masvingo, Chinhoyi, and more."
    );

    public boolean isFaqQuestion(String text) {
        if (text == null) return false;
        String key = normalise(text);
        return faqMap.keySet().stream().anyMatch(key::contains);
    }

    public String answer(String text) {
        if (text == null) return defaultFaqs();
        String key = normalise(text);
        return faqMap.entrySet().stream()
                .filter(e -> key.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultFaqs());
    }

    public String defaultFaqs() {
        return """
                💬 *Buyza FAQs*

                🏪 *Stores:* Takealot, PnP, Checkers, Mr Price, Game & more
                💳 *Payment:* EcoCash, Mukuru, Bank transfer
                🚚 *Delivery:* 3–7 working days
                📦 *What to buy:* Groceries, clothing, electronics, car parts & more

                Reply *Menu* to place an order or *4* to chat with an agent.
                """;
    }

    private String normalise(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
    }
}