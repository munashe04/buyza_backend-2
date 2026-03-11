package com.munashe04.Buyza.service;

import com.munashe04.Buyza.entity.UserSession;
import com.munashe04.Buyza.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long SESSION_TIMEOUT_SECONDS = 3600; // 1 hour

    private final UserSessionRepository repo;

    public SessionService(UserSessionRepository repo) {
        this.repo = repo;
    }

    public WhatsAppService.State getState(String phoneNumber) {
        return repo.findById(phoneNumber)
                .filter(s -> !isExpired(s))
                .map(s -> {
                    try {
                        return WhatsAppService.State.valueOf(s.getState());
                    } catch (Exception e) {
                        return WhatsAppService.State.NONE;
                    }
                })
                .orElse(WhatsAppService.State.NONE);
    }

    public void setState(String phoneNumber, WhatsAppService.State state) {
        UserSession session = repo.findById(phoneNumber)
                .orElse(new UserSession(phoneNumber, state.name(), Instant.now()));
        session.setState(state.name());
        session.setUpdatedAt(Instant.now());
        repo.save(session);
    }

    private boolean isExpired(UserSession session) {
        long elapsed = Instant.now().getEpochSecond() - session.getUpdatedAt().getEpochSecond();
        return elapsed > SESSION_TIMEOUT_SECONDS;
    }

    @Scheduled(fixedRate = 3600000) // every hour
    public void cleanupExpiredSessions() {
        long now    = Instant.now().getEpochSecond();
        int  before = (int) repo.count();
        repo.findAll().stream()
            .filter(this::isExpired)
            .forEach(repo::delete);
        log.info("Session cleanup done. Before: {}, After: {}", before, repo.count());
    }
}