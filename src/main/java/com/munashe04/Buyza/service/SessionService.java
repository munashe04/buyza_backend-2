package com.munashe04.Buyza.service;

import com.munashe04.Buyza.entity.UserSession;
import com.munashe04.Buyza.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long SESSION_TIMEOUT_SECONDS = 3600;

    // Fallback in-memory store if DB is unavailable
    private final ConcurrentHashMap<String, UserSession> memoryStore = new ConcurrentHashMap<>();

    private final Optional<UserSessionRepository> repo;
    private boolean dbAvailable = false;

    // Constructor — repo is optional
    public SessionService(Optional<UserSessionRepository> repo) {
        this.repo = repo;
        this.dbAvailable = repo.isPresent();
        if (dbAvailable) {
            log.info("✅ SessionService using PostgreSQL (Supabase)");
        } else {
            log.warn("⚠️ SessionService using in-memory store — sessions will not persist across restarts");
        }
    }

    public WhatsAppService.State getState(String phoneNumber) {
        try {
            if (dbAvailable) {
                return repo.get().findById(phoneNumber)
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
        } catch (Exception e) {
            log.warn("DB read failed, falling back to memory: {}", e.getMessage());
            dbAvailable = false;
        }

        // Fallback to memory
        UserSession session = memoryStore.get(phoneNumber);
        if (session == null || isExpired(session)) return WhatsAppService.State.NONE;
        try {
            return WhatsAppService.State.valueOf(session.getState());
        } catch (Exception e) {
            return WhatsAppService.State.NONE;
        }
    }

    public void setState(String phoneNumber, WhatsAppService.State state) {
        UserSession session = new UserSession(phoneNumber, state.name(), Instant.now());

        try {
            if (dbAvailable) {
                repo.get().save(session);
                return;
            }
        } catch (Exception e) {
            log.warn("DB write failed, falling back to memory: {}", e.getMessage());
            dbAvailable = false;
        }

        // Fallback to memory
        memoryStore.put(phoneNumber, session);
    }

    private boolean isExpired(UserSession session) {
        long elapsed = Instant.now().getEpochSecond() - session.getUpdatedAt().getEpochSecond();
        return elapsed > SESSION_TIMEOUT_SECONDS;
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredSessions() {
        // Cleanup memory store
        int before = memoryStore.size();
        memoryStore.entrySet().removeIf(e -> isExpired(e.getValue()));
        log.info("Memory session cleanup: removed {} expired sessions", before - memoryStore.size());

        // Cleanup DB store
        if (dbAvailable) {
            try {
                repo.get().findAll().stream()
                        .filter(this::isExpired)
                        .forEach(repo.get()::delete);
            } catch (Exception e) {
                log.warn("DB cleanup failed: {}", e.getMessage());
            }
        }
    }
}