package com.munashe04.Buyza.service;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    // Extremely simple: check if text contains common phrases "paid", "payment", or numeric ref pattern
    public boolean isPaymentConfirmation(String text) {
        if (text == null) return false;
        String low = text.toLowerCase();
        if (low.contains("paid") || low.contains("payment") || low.matches(".*[A-Za-z0-9]{6,}.*")) {
            return true;
        }
        return false;
    }
}
