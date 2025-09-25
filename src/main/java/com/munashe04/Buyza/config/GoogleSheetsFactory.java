package com.munashe04.Buyza.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class GoogleSheetsFactory {

    public static Sheets createSheetsServiceFromBase64() throws Exception {
        String b64 = System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON_BASE64");
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("GOOGLE_SERVICE_ACCOUNT_JSON_BASE64 not set");
        }
        byte[] jsonBytes = java.util.Base64.getDecoder().decode(b64);
        try (ByteArrayInputStream stream = new ByteArrayInputStream(jsonBytes)) {
            ServiceAccountCredentials credentials = (ServiceAccountCredentials) ServiceAccountCredentials.fromStream(stream)
                    .createScoped(List.of(SheetsScopes.SPREADSHEETS));
            var httpRequestInitializer = new HttpCredentialsAdapter(credentials);
            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JacksonFactory.getDefaultInstance(),
                    httpRequestInitializer)
                    .setApplicationName("BuyzaBot")
                    .build();
        }
    }
}
