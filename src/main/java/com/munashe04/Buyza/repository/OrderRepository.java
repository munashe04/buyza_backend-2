package com.munashe04.Buyza.repository;

import com.munashe04.Buyza.moddel.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByCustomerPhoneOrderByCreatedAtDesc(String customerPhone);
}
