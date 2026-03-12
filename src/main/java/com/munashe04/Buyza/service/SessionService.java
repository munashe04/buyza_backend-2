package com.munashe04.Buyza.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long SESSION_TIMEOUT_SECONDS = 3600;

    private record Session(WhatsAppService.State state, Instant updatedAt) {}
    private final ConcurrentHashMap<String, Session> store = new ConcurrentHashMap<>();

    public WhatsAppService.State getState(String phoneNumber) {
        Session session = store.get(phoneNumber);
        if (session == null) return WhatsAppService.State.NONE;
        long elapsed = Instant.now().getEpochSecond() - session.updatedAt().getEpochSecond();
        if (elapsed > SESSION_TIMEOUT_SECONDS) {
            store.remove(phoneNumber);
            return WhatsAppService.State.NONE;
        }
        return session.state();
    }

    public void setState(String phoneNumber, WhatsAppService.State state) {
        store.put(phoneNumber, new Session(state, Instant.now()));
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredSessions() {
        long now = Instant.now().getEpochSecond();
        int before = store.size();
        store.entrySet().removeIf(e ->
                now - e.getValue().updatedAt().getEpochSecond() > SESSION_TIMEOUT_SECONDS);
        log.info("Session cleanup: removed {} expired sessions", before - store.size());
    }
}