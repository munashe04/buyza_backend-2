package com.munashe04.Buyza.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "state")
    private String state;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public UserSession() {}

    public UserSession(String phoneNumber, String state, Instant updatedAt) {
        this.phoneNumber = phoneNumber;
        this.state       = state;
        this.updatedAt   = updatedAt;
    }

    public String getPhoneNumber() { return phoneNumber; }
    public String getState()       { return state; }
    public Instant getUpdatedAt()  { return updatedAt; }

    public void setState(String state)         { this.state = state; }
    public void setUpdatedAt(Instant updatedAt){ this.updatedAt = updatedAt; }
}