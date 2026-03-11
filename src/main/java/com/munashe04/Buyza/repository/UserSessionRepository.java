package com.munashe04.Buyza.repository;

import com.munashe04.Buyza.entity.UserSession;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, String> {}