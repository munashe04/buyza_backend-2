package com.munashe04.Buyza.service;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GoogleSheetsService
 * - Creates "Customers" and "Orders" sheets if missing.
 * - Appends new orders (never overwrites previous orders).
 * - Updates specific columns safely by reading the current row, modifying values, and writing back.
 * - Keeps a customer profile with Total Orders count and last interaction.
 */
@Service
public class GoogleSheetsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Sheets sheets;
    private final String spreadsheetId;

    private static final String CUSTOMERS_SHEET = "Customers";
    private static final String ORDERS_SHEET = "Orders";

    private static final List<String> CUSTOMER_HEADERS = List.of(
            "Phone Number", "Customer Name", "Total Orders", "Last Interaction",
            "Current Status", "Preferred Town", "Customer Tier", "Agent Notes", "Last Updated"
    );

    private static final List<String> ORDER_HEADERS = List.of(
            "Order ID", "Phone Number", "Order Type", "Order Details", "Quote Amount",
            "Payment Status", "Order Status", "Delivery Town", "Created Date", "Last Updated"
    );

    private static final Set<String> ACTIVE_ORDER_STATUSES = new HashSet<>(List.of(
            "New", "Pending", "Awaiting Details", "Details Provided", "Quote Sent",
            "Awaiting Payment", "Payment Pending", "Processing"
    ));

    private static final Set<String> COMPLETED_ORDER_STATUSES = new HashSet<>(List.of(
            "Completed", "Delivered", "Cancelled", "Rejected", "Expired"
    ));

    public GoogleSheetsService(Sheets sheets, @Value("${google.sheets.spreadsheet-id}") String spreadsheetId) {
        this.sheets = sheets;
        this.spreadsheetId = spreadsheetId;
        initializeSheets();
    }

    // ---------- Initialization ----------

    private void initializeSheets() {
        try {
            createSheetIfNotExists(CUSTOMERS_SHEET, CUSTOMER_HEADERS);
            createSheetIfNotExists(ORDERS_SHEET, ORDER_HEADERS);
        } catch (Exception e) {
            log.error("Error initializing sheets: {}", e.getMessage(), e);
        }
    }

    private void createSheetIfNotExists(String sheetName, List<String> headers) {
        try {
            // Try to read A1. If it fails, create the sheet
            sheets.spreadsheets().values().get(spreadsheetId, sheetName + "!A1").execute();
            log.info("Sheet '{}' exists", sheetName);
        } catch (Exception e) {
            createNewSheet(sheetName, headers);
        }
    }

    private void createNewSheet(String sheetName, List<String> headers) {
        try {
            AddSheetRequest addSheetRequest = new AddSheetRequest();
            addSheetRequest.setProperties(new SheetProperties().setTitle(sheetName));

            BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest()
                    .setRequests(List.of(new Request().setAddSheet(addSheetRequest)));

            sheets.spreadsheets().batchUpdate(spreadsheetId, batchRequest).execute();

            ValueRange headerBody = new ValueRange().setValues(List.of());
            sheets.spreadsheets().values()
                    .update(spreadsheetId, sheetName + "!A1", headerBody)
                    .setValueInputOption("RAW").execute();

            log.info("Created sheet '{}' with headers", sheetName);
        } catch (Exception ex) {
            log.error("Failed to create sheet '{}': {}", sheetName, ex.getMessage(), ex);
        }
    }

    // ---------- Public API ----------

    /**
     * Top-level method called by WhatsAppService.
     * interactionType: "Online Order Start", "Online Order Details", "Assisted Order Start", "Greeting", etc.
     * phoneNumber: WhatsApp sender number (use E.164 if possible).
     * userMessage: raw user message text.
     * quote: quote string if present (agent-provided).
     * status: suggested status to set (e.g., "New", "Pending Quote", "Details Provided", "Escalated").
     */
    public boolean saveInteraction(String interactionType, String phoneNumber, String userMessage, String quote, String status) {
        return handleCustomerInteraction(phoneNumber, interactionType, userMessage, quote, status);
    }

    // ---------- Core logic ----------

    private boolean handleCustomerInteraction(String phoneNumber, String interactionType,
                                              String userMessage, String quote, String status) {
        try {
            // 1) Update/create customer profile (Last Interaction, status)
            updateCustomerProfile(phoneNumber, userMessage, status);

            // 2) Decide whether to create a new order or update existing
            boolean createNew = shouldCreateNewOrder(phoneNumber, interactionType, userMessage);

            if (createNew) {
                log.info("Creating new order for {} (type: {})", phoneNumber, determineOrderType(interactionType, userMessage));
                return createNewOrder(phoneNumber, determineOrderType(interactionType, userMessage), userMessage, quote, "New");
            } else {
                // update existing active order (if any)
                return handleExistingOrderInteraction(phoneNumber, interactionType, userMessage, quote, status);
            }
        } catch (Exception e) {
            log.error("Failed handleCustomerInteraction for {}: {}", phoneNumber, e.getMessage(), e);
            return false;
        }
    }

    private boolean shouldCreateNewOrder(String phoneNumber, String interactionType, String userMessage) throws IOException {
        // Explicit new order starts (menu selection "Online Order Start" or "Assisted Order Start")
        if (interactionType != null && (interactionType.contains("Start") || interactionType.toLowerCase().contains("new"))) {
            return true;
        }

        // If message clearly contains order/cart details and no active orders -> create
        if (isOrderRelatedContent(userMessage) && !hasActiveOrders(phoneNumber)) {
            return true;
        }

        // Otherwise, do not auto-create; we prefer to wait for explicit start
        return false;
    }

    private boolean hasActiveOrders(String phoneNumber) throws IOException {
        List<OrderInfo> orders = getCustomerOrders(phoneNumber);
        for (OrderInfo o : orders) if (isActiveOrderStatus(o.status)) return true;
        return false;
    }

    private List<OrderInfo> getCustomerOrders(String phoneNumber) throws IOException {
        List<OrderInfo> orders = new ArrayList<>();
        ValueRange response = sheets.spreadsheets().values().get(spreadsheetId, ORDERS_SHEET + "!A2:J").execute();
        List<List<Object>> values = response.getValues();
        if (values == null) return orders;

        for (List<Object> row : values) {
            // row layout: A=OrderID, B=Phone, C=Type, ... G=OrderStatus (index 6)
            if (row.size() >= 7) {
                String phone = row.get(1) == null ? "" : row.get(1).toString();
                if (phone.equals(phoneNumber)) {
                    String id = row.get(0) == null ? "" : row.get(0).toString();
                    String status = row.get(6) == null ? "" : row.get(6).toString();
                    orders.add(new OrderInfo(id, status));
                }
            }
        }
        return orders;
    }

    private boolean handleExistingOrderInteraction(String phoneNumber, String interactionType,
                                                   String userMessage, String quote, String status) throws IOException {
        OrderInfo latest = getLatestActiveOrder(phoneNumber);
        if (latest == null) {
            // no active order to update; create new if content clearly order related
            if (isOrderRelatedContent(userMessage)) {
                return createNewOrder(phoneNumber, determineOrderType(interactionType, userMessage), userMessage, quote, "New");
            }
            return false;
        }

        // Decide action by message or interactionType
        if (interactionType != null && interactionType.toLowerCase().contains("details")) {
            return updateOrderDetails(latest.id, userMessage, quote);
        }

        if ("yes".equalsIgnoreCase(userMessage) || (interactionType != null && interactionType.toLowerCase().contains("accept"))) {
            return updateOrderPaymentStatus(latest.id, "Awaiting Payment");
        }

        if ("no".equalsIgnoreCase(userMessage) || (interactionType != null && interactionType.toLowerCase().contains("cancel"))) {
            return updateOrderStatus(latest.id, "Cancelled");
        }

        // default: update last interaction / append notes
        return updateOrderLastInteraction(latest.id, userMessage);
    }

    private OrderInfo getLatestActiveOrder(String phoneNumber) throws IOException {
        List<OrderInfo> orders = getCustomerOrders(phoneNumber);
        for (int i = orders.size() - 1; i >= 0; i--) {
            if (isActiveOrderStatus(orders.get(i).status)) return orders.get(i);
        }
        return null;
    }

    // ---------- Order operations ----------

    private boolean createNewOrder(String phoneNumber, String orderType, String userMessage,
                                   String quote, String status) throws IOException {
        String orderId = generateOrderId(phoneNumber);

        List<Object> newRow = new ArrayList<>(10);
        newRow.add(orderId); // A
        newRow.add(phoneNumber); // B
        newRow.add(orderType); // C
        newRow.add(extractOrderDetails(userMessage)); // D
        newRow.add(quote == null || quote.equals("-") ? "" : quote); // E
        newRow.add("Pending"); // F - payment status
        newRow.add(status == null ? "New" : status); // G - order status
        newRow.add(extractDeliveryTown(userMessage)); // H
        newRow.add(LocalDateTime.now().format(TIMESTAMP_FORMAT)); // I created date
        newRow.add(LocalDateTime.now().format(TIMESTAMP_FORMAT)); // J last updated

        ValueRange body = new ValueRange().setValues(List.of(newRow));
        sheets.spreadsheets().values()
                .append(spreadsheetId, ORDERS_SHEET + "!A:J", body)
                .setValueInputOption("RAW")
                .execute();

        // increment customer's total orders count
        incrementCustomerOrderCount(phoneNumber);

        log.info("Created order {} for {}", orderId, phoneNumber);
        return true;
    }

    private void incrementCustomerOrderCount(String phoneNumber) {
        try {
            int row = findCustomerRow(phoneNumber);
            if (row == -1) return;

            String cellRange = CUSTOMERS_SHEET + "!C" + row; // Total Orders column C
            ValueRange resp = sheets.spreadsheets().values().get(spreadsheetId, cellRange).execute();
            int current = 0;
            if (resp.getValues() != null && !resp.getValues().isEmpty() && resp.getValues().get(0).size() > 0) {
                try { current = Integer.parseInt(resp.getValues().get(0).get(0).toString()); } catch (NumberFormatException ignored) {}
            }
            ValueRange body = new ValueRange().setValues(List.of(List.of(String.valueOf(current + 1))));
            sheets.spreadsheets().values().update(spreadsheetId, cellRange, body).setValueInputOption("RAW").execute();
        } catch (Exception e) {
            log.warn("Failed to increment order count for {}: {}", phoneNumber, e.getMessage());
        }
    }

    private boolean updateOrderDetails(String orderId, String userMessage, String quote) throws IOException {
        int row = findOrderRowById(orderId);
        if (row == -1) return false;

        // Read full row, modify columns D (index 3) and E (index 4) and J (index 9)
        List<Object> existing = readOrderRow(row);
        if (existing == null) return false;

        // ensure list has size 10
        while (existing.size() < 10) existing.add("");

        existing.set(3, extractOrderDetails(userMessage)); // D
        if (quote != null && !quote.equals("-")) existing.set(4, quote); // E
        existing.set(9, LocalDateTime.now().format(TIMESTAMP_FORMAT)); // J

        ValueRange body = new ValueRange().setValues(List.of(existing));
        sheets.spreadsheets().values().update(spreadsheetId, ORDERS_SHEET + "!A" + row + ":J" + row, body)
                .setValueInputOption("RAW").execute();
        return true;
    }

    private boolean updateOrderPaymentStatus(String orderId, String paymentStatus) throws IOException {
        int row = findOrderRowById(orderId);
        if (row == -1) return false;

        List<Object> existing = readOrderRow(row);
        if (existing == null) return false;

        while (existing.size() < 10) existing.add("");
        existing.set(5, paymentStatus); // F
        existing.set(6, "Awaiting Payment"); // G
        existing.set(9, LocalDateTime.now().format(TIMESTAMP_FORMAT)); // J

        ValueRange body = new ValueRange().setValues(List.of(existing));
        sheets.spreadsheets().values().update(spreadsheetId, ORDERS_SHEET + "!A" + row + ":J" + row, body)
                .setValueInputOption("RAW").execute();
        return true;
    }

    private boolean updateOrderStatus(String orderId, String newStatus) throws IOException {
        int row = findOrderRowById(orderId);
        if (row == -1) return false;

        List<Object> existing = readOrderRow(row);
        if (existing == null) return false;

        while (existing.size() < 10) existing.add("");
        existing.set(6, newStatus); // G
        existing.set(9, LocalDateTime.now().format(TIMESTAMP_FORMAT)); // J

        ValueRange body = new ValueRange().setValues(List.of(existing));
        sheets.spreadsheets().values().update(spreadsheetId, ORDERS_SHEET + "!A" + row + ":J" + row, body)
                .setValueInputOption("RAW").execute();
        return true;
    }

    private boolean updateOrderLastInteraction(String orderId, String userMessage) throws IOException {
        int row = findOrderRowById(orderId);
        if (row == -1) return false;

        List<Object> existing = readOrderRow(row);
        if (existing == null) return false;

        while (existing.size() < 10) existing.add("");
        // Append interaction snippet to details
        String currentDetails = existing.get(3) == null ? "" : existing.get(3).toString();
        String appended = (currentDetails.isBlank() ? "" : currentDetails + " || ") + extractOrderDetails(userMessage);
        existing.set(3, appended); // D
        existing.set(9, LocalDateTime.now().format(TIMESTAMP_FORMAT)); // J

        ValueRange body = new ValueRange().setValues(List.of(existing));
        sheets.spreadsheets().values().update(spreadsheetId, ORDERS_SHEET + "!A" + row + ":J" + row, body)
                .setValueInputOption("RAW").execute();
        return true;
    }

    private List<Object> readOrderRow(int row) throws IOException {
        ValueRange resp = sheets.spreadsheets().values().get(spreadsheetId, ORDERS_SHEET + "!A" + row + ":J" + row).execute();
        List<List<Object>> vals = resp.getValues();
        if (vals == null || vals.isEmpty()) return null;
        return new ArrayList<>(vals.get(0));
    }

    private int findOrderRowById(String orderId) throws IOException {
        ValueRange response = sheets.spreadsheets().values().get(spreadsheetId, ORDERS_SHEET + "!A2:A").execute();
        List<List<Object>> orders = response.getValues();
        if (orders == null) return -1;
        for (int i = 0; i < orders.size(); i++) {
            List<Object> row = orders.get(i);
            if (row != null && !row.isEmpty() && orderId.equals(String.valueOf(row.get(0)))) return i + 2;
        }
        return -1;
    }

    // ---------- Customer profile methods ----------

    private void updateCustomerProfile(String phoneNumber, String userMessage, String status) {
        try {
            int row = findCustomerRow(phoneNumber);
            if (row == -1) {
                createNewCustomer(phoneNumber, userMessage, status);
            } else {
                // Read existing to preserve fields
                ValueRange resp = sheets.spreadsheets().values().get(spreadsheetId, CUSTOMERS_SHEET + "!A" + row + ":I" + row).execute();
                List<List<Object>> vals = resp.getValues();
                List<Object> existing = (vals == null || vals.isEmpty()) ? new ArrayList<>() : new ArrayList<>(vals.get(0));
                while (existing.size() < CUSTOMER_HEADERS.size()) existing.add("");

                existing.set(3, userMessage == null ? existing.get(3) : userMessage); // D Last Interaction
                existing.set(4, status == null ? existing.get(4) : status); // E Current Status
                existing.set(8, LocalDateTime.now().format(TIMESTAMP_FORMAT)); // I Last Updated

                ValueRange body = new ValueRange().setValues(List.of(existing));
                sheets.spreadsheets().values().update(spreadsheetId, CUSTOMERS_SHEET + "!A" + row + ":I" + row, body)
                        .setValueInputOption("RAW").execute();
            }
        } catch (Exception e) {
            log.warn("Failed to update customer profile for {}: {}", phoneNumber, e.getMessage());
        }
    }

    private int findCustomerRow(String phoneNumber) throws IOException {
        ValueRange response = sheets.spreadsheets().values().get(spreadsheetId, CUSTOMERS_SHEET + "!A2:A").execute();
        List<List<Object>> customers = response.getValues();
        if (customers == null) return -1;
        for (int i = 0; i < customers.size(); i++) {
            List<Object> row = customers.get(i);
            if (row != null && !row.isEmpty() && phoneNumber.equals(String.valueOf(row.get(0)))) return i + 2;
        }
        return -1;
    }

    private void createNewCustomer(String phoneNumber, String userMessage, String status) {
        try {
            List<Object> newCustomer = new ArrayList<>();
            newCustomer.add(phoneNumber); // A
            newCustomer.add(""); // B customer name
            newCustomer.add("0"); // C total orders (incremented on create order)
            newCustomer.add(userMessage == null ? "" : userMessage); // D last interaction
            newCustomer.add(status == null ? "" : status); // E current status
            newCustomer.add(extractDeliveryTown(userMessage)); // F preferred town
            newCustomer.add("New"); // G customer tier
            newCustomer.add(""); // H agent notes
            newCustomer.add(LocalDateTime.now().format(TIMESTAMP_FORMAT)); // I last updated

            ValueRange body = new ValueRange().setValues(List.of(newCustomer));
            sheets.spreadsheets().values().append(spreadsheetId, CUSTOMERS_SHEET + "!A:I", body)
                    .setValueInputOption("RAW").execute();
        } catch (Exception e) {
            log.warn("Failed to create customer {} : {}", phoneNumber, e.getMessage());
        }
    }

    // ---------- Helpers ----------

    private String determineOrderType(String interactionType, String userMessage) {
        String lower = (userMessage == null) ? "" : userMessage.toLowerCase();
        if (interactionType != null && interactionType.toLowerCase().contains("assisted")) return "Assisted Order";
        if (interactionType != null && interactionType.toLowerCase().contains("online")) return "Online Order";
        if (lower.contains("cart") || lower.contains("takealot") || lower.contains("pnP") || lower.contains("cart link")) return "Online Order";
        if (lower.contains("need") || lower.contains("help") || lower.contains("looking for")) return "Assisted Order";
        return "General Order";
    }

    private String generateOrderId(String phoneNumber) {
        String last4 = phoneNumber == null ? "0000" : (phoneNumber.length() >= 4 ? phoneNumber.substring(phoneNumber.length() - 4) : phoneNumber);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "BUYZA-" + last4 + "-" + ts;
    }

    private String extractOrderDetails(String message) {
        if (message == null) return "";
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }

    private String extractDeliveryTown(String message) {
        if (message == null) return "";
        String lower = message.toLowerCase();
        List<String> towns = List.of("harare", "bulawayo", "gweru", "mutare", "masvingo", "chinhoyi");
        for (String t : towns) if (lower.contains(t)) return Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return "";
    }

    private boolean isOrderRelatedContent(String message) {
        if (message == null) return false;
        String low = message.toLowerCase();
        return low.contains("cart") || low.contains("total") || low.contains("order") || low.contains("buy")
                || low.contains("product") || low.contains("takealot") || low.contains("pnp") || low.contains("http");
    }

    private boolean isActiveOrderStatus(String status) {
        if (status == null) return false;
        return ACTIVE_ORDER_STATUSES.contains(status.trim());
    }

    // ---------- Simple DTO ----------
    private static class OrderInfo {
        String id;
        String status;
        OrderInfo(String id, String status) { this.id = id; this.status = status; }
    }
}
