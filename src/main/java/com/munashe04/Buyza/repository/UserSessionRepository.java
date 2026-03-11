package com.munashe04.Buyza.repository;

import com.munashe04.Buyza.entity.UserSession;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public interface UserSessionRepository extends JpaRepository<UserSession, String> {}