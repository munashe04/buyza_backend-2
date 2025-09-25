package com.munashe04.Buyza.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Base64;

@Configuration
public class GoogleSheetsConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsConfig.class);

    @Bean
    public Sheets sheetsService() throws IOException, GeneralSecurityException {
        String encodedCreds = System.getenv("GOOGLE_CREDENTIALS_BASE64");
        if (encodedCreds == null || encodedCreds.isEmpty()) {
            throw new IllegalStateException("❌ Missing GOOGLE_CREDENTIALS_BASE64 environment variable");
        }

        log.info("🔑 Decoding Google credentials from environment...");

        byte[] decodedBytes = Base64.getDecoder().decode(encodedCreds);
        ByteArrayInputStream credentialsStream = new ByteArrayInputStream(decodedBytes);

        GoogleCredential credential = GoogleCredential.fromStream(credentialsStream)
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

        Sheets sheets = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                jsonFactory,
                credential
        )
                .setApplicationName("Buyza Bot")
                .build();

        log.info("✅ Google Sheets service initialized successfully");
        return sheets;
    }
}
