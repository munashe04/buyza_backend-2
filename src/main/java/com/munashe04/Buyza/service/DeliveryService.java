package com.munashe04.Buyza.service;

import org.springframework.stereotype.Service;

@Service
public class DeliveryService {

    public String getDeliveryTimeline(String town) {
        switch (town.toLowerCase()) {
            case "harare": return "3 business days";
            case "bulawayo": return "4 business days";
            case "gweru": return "3-5 business days";
            default: return "up to 7 business days";
        }
    }
}
