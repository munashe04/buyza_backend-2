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
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Configuration
public class GoogleSheetsConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsConfig.class);

    @Bean
    public Sheets sheetsService() throws IOException, GeneralSecurityException {
        log.info("🔑 Loading Google credentials from classpath...");

        ClassPathResource resource = new ClassPathResource("buyza-bot.json");

        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Google credentials file 'buyza-bot.json' not found in classpath. " +
                            "Please place your credentials file in src/main/resources/buyza-bot.json"
            );
        }

        log.info("✅ Found credentials file: buyza-bot.json");

        try (InputStream credentialsStream = resource.getInputStream()) {
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
}