package com.munashe04.Buyza.moddel;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // WhatsApp user phone e.g., "26377xxxxxxx"
    private String customerPhone;

    // raw cart or description
    @Column(columnDefinition = "text")
    private String details;

    private BigDecimal goodsValue;
    private BigDecimal serviceFee;
    private BigDecimal deliveryFee;
    private BigDecimal subtotal;
    private String deliveryTown;

    @Enumerated(EnumType.STRING)
    private Status status;

    private OffsetDateTime createdAt;

    public enum Status {
        NEW, AWAITING_PAYMENT, PAID, PROCESSING, SHIPPED, DELIVERED, CANCELLED
    }
}
