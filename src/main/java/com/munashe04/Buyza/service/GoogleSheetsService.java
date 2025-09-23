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
import java.util.ArrayList;
import java.util.List;

@Service
public class GoogleSheetsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Sheets sheets;
    private final String spreadsheetId;

    private static final String CUSTOMERS_SHEET = "Customers";
    private static final String ORDERS_SHEET = "Orders";

    private static final String[] CUSTOMER_HEADERS = {
            "Phone Number", "Customer Name", "Total Orders", "Last Interaction",
            "Current Status", "Preferred Town", "Customer Tier", "Agent Notes", "Last Updated"
    };

    private static final String[] ORDER_HEADERS = {
            "Order ID", "Phone Number", "Order Type", "Order Details", "Quote Amount",
            "Payment Status", "Order Status", "Delivery Town", "Created Date", "Last Updated"
    };

    // Order statuses that indicate an active order (should not create new one)
    private static final String[] ACTIVE_ORDER_STATUSES = {
            "New", "Pending", "Awaiting Details", "Details Provided", "Quote Sent",
            "Awaiting Payment", "Payment Pending", "Processing"
    };

    // Order statuses that indicate completed/cancelled order (can create new one)
    private static final String[] COMPLETED_ORDER_STATUSES = {
            "Completed", "Delivered", "Cancelled", "Rejected", "Expired"
    };

    public GoogleSheetsService(Sheets sheets, @Value("${google.sheets.spreadsheet-id}") String spreadsheetId) {
        this.sheets = sheets;
        this.spreadsheetId = spreadsheetId;
        initializeSheets();
    }

    private void initializeSheets() {
        createSheetIfNotExists(CUSTOMERS_SHEET, CUSTOMER_HEADERS);
        createSheetIfNotExists(ORDERS_SHEET, ORDER_HEADERS);
    }

    private void createSheetIfNotExists(String sheetName, String[] headers) {
        try {
            sheets.spreadsheets().values().get(spreadsheetId, sheetName + "!A1").execute();
            log.info("✅ Sheet '{}' exists", sheetName);
        } catch (Exception e) {
            createNewSheet(sheetName, headers);
        }
    }

    private void createNewSheet(String sheetName, String[] headers) {
        try {
            AddSheetRequest addSheetRequest = new AddSheetRequest();
            addSheetRequest.setProperties(new SheetProperties().setTitle(sheetName));

            BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest();
            batchRequest.setRequests(List.of(new Request().setAddSheet(addSheetRequest)));

            sheets.spreadsheets().batchUpdate(spreadsheetId, batchRequest).execute();

            List<Object> headerRow = new ArrayList<>();
            for (String header : headers) headerRow.add(header);

            ValueRange headerBody = new ValueRange().setValues(List.of(headerRow));
            sheets.spreadsheets().values()
                    .update(spreadsheetId, sheetName + "!A1", headerBody)
                    .setValueInputOption("RAW")
                    .execute();

            log.info("✅ Created sheet '{}' with headers", sheetName);
        } catch (Exception e) {
            log.error("❌ Failed to create sheet '{}': {}", sheetName, e.getMessage());
        }
    }

    /**
     * Main method to handle customer interactions with intelligent order detection
     */
    public boolean handleCustomerInteraction(String phoneNumber, String interactionType,
                                             String userMessage, String quote, String status) {
        try {
            // First, update customer profile
            updateCustomerProfile(phoneNumber, userMessage, status);

            // Determine if this should create a new order or continue existing one
            boolean shouldCreateNewOrder = shouldCreateNewOrder(phoneNumber, interactionType, userMessage);

            if (shouldCreateNewOrder) {
                log.info("🆕 Creating new order for {} - interaction: {}", phoneNumber, interactionType);
                return createNewOrder(phoneNumber, determineOrderType(interactionType, userMessage),
                        userMessage, quote, "New");
            } else {
                // Continue with existing order
                return handleExistingOrderInteraction(phoneNumber, interactionType, userMessage, quote, status);
            }

        } catch (Exception e) {
            log.error("Failed to handle customer interaction: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Smart detection: when to create a new order vs continue existing one
     */
    private boolean shouldCreateNewOrder(String phoneNumber, String interactionType, String userMessage) throws IOException {
        // If this is explicitly a new order start
        if (interactionType.contains("Start") || interactionType.contains("New Order")) {
            return true;
        }

        // If user sends generic message like "Hi" but has active orders, continue existing
        if (isGenericGreeting(userMessage) && hasActiveOrders(phoneNumber)) {
            log.info("👋 Customer {} sent greeting but has active orders - continuing existing order", phoneNumber);
            return false;
        }

        // If user sends generic message and has NO active orders, start new conversation
        if (isGenericGreeting(userMessage) && !hasActiveOrders(phoneNumber)) {
            log.info("👋 Customer {} sent greeting with no active orders - starting new conversation", phoneNumber);
            return false; // Don't create order yet, wait for them to choose option 1/2
        }

        // If this is order-related content but user has active orders, continue existing
        if (isOrderRelatedContent(userMessage) && hasActiveOrders(phoneNumber)) {
            log.info("📦 Customer {} sent order content with active orders - continuing existing order", phoneNumber);
            return false;
        }

        // If this is order-related content and user has NO active orders, create new order
        if (isOrderRelatedContent(userMessage) && !hasActiveOrders(phoneNumber)) {
            log.info("📦 Customer {} sent order content with no active orders - creating new order", phoneNumber);
            return true;
        }

        // Default: don't create new order for generic interactions
        return false;
    }

    /**
     * Check if user has active orders (orders that are not completed/cancelled)
     */
    private boolean hasActiveOrders(String phoneNumber) throws IOException {
        List<OrderInfo> orders = getCustomerOrders(phoneNumber);

        for (OrderInfo order : orders) {
            if (isActiveOrderStatus(order.status)) {
                log.info("📋 Customer {} has active order: {} - {}", phoneNumber, order.id, order.status);
                return true;
            }
        }

        return false;
    }

    /**
     * Get all orders for a customer
     */
    private List<OrderInfo> getCustomerOrders(String phoneNumber) throws IOException {
        List<OrderInfo> orders = new ArrayList<>();

        ValueRange response = sheets.spreadsheets().values()
                .get(spreadsheetId, ORDERS_SHEET + "!A2:J")
                .execute();

        List<List<Object>> orderData = response.getValues();
        if (orderData == null) return orders;

        for (List<Object> row : orderData) {
            if (row.size() >= 7 && phoneNumber.equals(row.get(1).toString())) {
                orders.add(new OrderInfo(
                        row.get(0).toString(), // orderId
                        row.get(6).toString()  // status
                ));
            }
        }

        return orders;
    }

    /**
     * Handle interaction with existing order
     */
    private boolean handleExistingOrderInteraction(String phoneNumber, String interactionType,
                                                   String userMessage, String quote, String status) throws IOException {
        OrderInfo latestActiveOrder = getLatestActiveOrder(phoneNumber);

        if (latestActiveOrder == null) {
            log.warn("No active order found for {} but shouldCreateNewOrder returned false", phoneNumber);
            return false;
        }

        log.info("📝 Updating existing order {} for customer {}", latestActiveOrder.id, phoneNumber);

        if (interactionType.contains("Details") || isOrderDetails(userMessage)) {
            return updateOrderDetails(latestActiveOrder.id, userMessage, quote);
        } else if (interactionType.contains("Payment") || "yes".equalsIgnoreCase(userMessage)) {
            return updateOrderPaymentStatus(latestActiveOrder.id, "Paid");
        } else if (interactionType.contains("Cancel") || "no".equalsIgnoreCase(userMessage)) {
            return updateOrderStatus(latestActiveOrder.id, "Cancelled");
        } else {
            // Generic message - just update last interaction
            return updateOrderLastInteraction(latestActiveOrder.id, userMessage);
        }
    }

    /**
     * Get the latest active order for a customer
     */
    private OrderInfo getLatestActiveOrder(String phoneNumber) throws IOException {
        List<OrderInfo> orders = getCustomerOrders(phoneNumber);

        // Return the most recent active order
        for (int i = orders.size() - 1; i >= 0; i--) {
            OrderInfo order = orders.get(i);
            if (isActiveOrderStatus(order.status)) {
                return order;
            }
        }

        return null;
    }

    // Helper methods
    private boolean isGenericGreeting(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.equals("hi") || lower.equals("hello") || lower.equals("hey") ||
                lower.equals("start") || lower.contains("help");
    }

    private boolean isOrderRelatedContent(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("cart") || lower.contains("order") || lower.contains("buy") ||
                lower.contains("product") || lower.contains("item") || lower.contains("want") ||
                lower.contains("need") || lower.contains("looking for") || lower.contains("total:");
    }

    private boolean isOrderDetails(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("cart link:") || lower.contains("total:") ||
                lower.contains("delivery:") || lower.contains("http");
    }

    private boolean isActiveOrderStatus(String status) {
        if (status == null) return false;
        for (String activeStatus : ACTIVE_ORDER_STATUSES) {
            if (activeStatus.equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCompletedOrderStatus(String status) {
        if (status == null) return false;
        for (String completedStatus : COMPLETED_ORDER_STATUSES) {
            if (completedStatus.equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    // Existing methods from previous implementation (createNewOrder, updateOrderDetails, etc.)
    private boolean createNewOrder(String phoneNumber, String orderType, String userMessage,
                                   String quote, String status) throws IOException {
        String orderId = generateOrderId(phoneNumber);

        List<Object> newOrder = new ArrayList<>();
        newOrder.add(orderId);
        newOrder.add(phoneNumber);
        newOrder.add(orderType);
        newOrder.add(extractOrderDetails(userMessage));
        newOrder.add(quote != null && !quote.equals("-") ? quote : "");
        newOrder.add("Pending");
        newOrder.add(status);
        newOrder.add(extractDeliveryTown(userMessage));
        newOrder.add(LocalDateTime.now().format(TIMESTAMP_FORMAT));
        newOrder.add(LocalDateTime.now().format(TIMESTAMP_FORMAT));

        ValueRange body = new ValueRange().setValues(List.of(newOrder));
        sheets.spreadsheets().values()
                .append(spreadsheetId, ORDERS_SHEET + "!A:J", body)
                .setValueInputOption("RAW")
                .execute();

        log.info("✅ Created new order {} for customer {}", orderId, phoneNumber);
        return true;
    }

    private boolean updateOrderDetails(String orderId, String userMessage, String quote) throws IOException {
        int orderRow = findOrderRowById(orderId);
        if (orderRow == -1) return false;

        String range = ORDERS_SHEET + "!D" + orderRow + ":E" + orderRow;
        List<Object> updateData = List.of(extractOrderDetails(userMessage),
                quote != null && !quote.equals("-") ? quote : "");

        ValueRange body = new ValueRange().setValues(List.of(updateData));
        sheets.spreadsheets().values().update(spreadsheetId, range, body).setValueInputOption("RAW").execute();

        return true;
    }

    private boolean updateOrderPaymentStatus(String orderId, String paymentStatus) throws IOException {
        int orderRow = findOrderRowById(orderId);
        if (orderRow == -1) return false;

        String range = ORDERS_SHEET + "!F" + orderRow + ":G" + orderRow;
        List<Object> updateData = List.of(paymentStatus, "Confirmed");

        ValueRange body = new ValueRange().setValues(List.of(updateData));
        sheets.spreadsheets().values().update(spreadsheetId, range, body).setValueInputOption("RAW").execute();

        return true;
    }

    private boolean updateOrderStatus(String orderId, String status) throws IOException {
        int orderRow = findOrderRowById(orderId);
        if (orderRow == -1) return false;

        String range = ORDERS_SHEET + "!G" + orderRow;
        ValueRange body = new ValueRange().setValues(List.of(List.of(status)));
        sheets.spreadsheets().values().update(spreadsheetId, range, body).setValueInputOption("RAW").execute();

        return true;
    }

    private boolean updateOrderLastInteraction(String orderId, String userMessage) throws IOException {
        int orderRow = findOrderRowById(orderId);
        if (orderRow == -1) return false;

        String range = ORDERS_SHEET + "!D" + orderRow + ":J" + orderRow;
        List<Object> updateData = new ArrayList<>();
        updateData.add(extractOrderDetails(userMessage)); // Update order details
        updateData.add(""); // Quote amount (keep existing)
        updateData.add(""); // Payment status (keep existing)
        updateData.add(""); // Order status (keep existing)
        updateData.add(""); // Delivery town (keep existing)
        updateData.add(""); // Created date (keep existing)
        updateData.add(LocalDateTime.now().format(TIMESTAMP_FORMAT)); // Update timestamp

        ValueRange body = new ValueRange().setValues(List.of(updateData));
        sheets.spreadsheets().values().update(spreadsheetId, range, body).setValueInputOption("RAW").execute();

        return true;
    }

    private int findOrderRowById(String orderId) throws IOException {
        ValueRange response = sheets.spreadsheets().values()
                .get(spreadsheetId, ORDERS_SHEET + "!A2:A")
                .execute();

        List<List<Object>> orders = response.getValues();
        if (orders == null) return -1;

        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i) != null && !orders.get(i).isEmpty() &&
                    orderId.equals(orders.get(i).get(0).toString())) {
                return i + 2;
            }
        }
        return -1;
    }

    // Customer profile methods
    private void updateCustomerProfile(String phoneNumber, String userMessage, String status) throws IOException {
        int customerRow = findCustomerRow(phoneNumber);
        if (customerRow == -1) {
            createNewCustomer(phoneNumber, userMessage, status);
        } else {
            updateExistingCustomer(customerRow, userMessage, status);
        }
    }

    private int findCustomerRow(String phoneNumber) throws IOException {
        ValueRange response = sheets.spreadsheets().values()
                .get(spreadsheetId, CUSTOMERS_SHEET + "!A2:A")
                .execute();
        List<List<Object>> customers = response.getValues();
        if (customers == null) return -1;

        for (int i = 0; i < customers.size(); i++) {
            if (customers.get(i) != null && !customers.get(i).isEmpty() &&
                    phoneNumber.equals(customers.get(i).get(0).toString())) {
                return i + 2;
            }
        }
        return -1;
    }

    private void createNewCustomer(String phoneNumber, String userMessage, String status) throws IOException {
        List<Object> newCustomer = List.of(phoneNumber, "", "1", userMessage, status,
                extractDeliveryTown(userMessage), "New", "",
                LocalDateTime.now().format(TIMESTAMP_FORMAT));
        ValueRange body = new ValueRange().setValues(List.of(newCustomer));
        sheets.spreadsheets().values().append(spreadsheetId, CUSTOMERS_SHEET + "!A:I", body)
                .setValueInputOption("RAW").execute();
    }

    private void updateExistingCustomer(int row, String userMessage, String status) throws IOException {
        String range = CUSTOMERS_SHEET + "!D" + row + ":E" + row;
        ValueRange body = new ValueRange().setValues(List.of(List.of(userMessage, status)));
        sheets.spreadsheets().values().update(spreadsheetId, range, body).setValueInputOption("RAW").execute();
    }

    // Helper methods
    private String determineOrderType(String interactionType, String userMessage) {
        if (interactionType.contains("Online") || userMessage.toLowerCase().contains("cart"))
            return "Online Order";
        if (interactionType.contains("Assisted") || userMessage.toLowerCase().contains("help"))
            return "Assisted Order";
        return "General Order";
    }

    private String generateOrderId(String phoneNumber) {
        return "ORD-" + phoneNumber.substring(Math.max(0, phoneNumber.length() - 4)) + "-" +
                System.currentTimeMillis() % 10000;
    }

    private String extractOrderDetails(String userMessage) {
        return userMessage.length() > 100 ? userMessage.substring(0, 100) + "..." : userMessage;
    }

    private String extractDeliveryTown(String userMessage) {
        String[] towns = {"harare", "bulawayo", "gweru", "mutare", "masvingo", "chinhoyi"};
        String lowerMessage = userMessage.toLowerCase();
        for (String town : towns) {
            if (lowerMessage.contains(town)) return town.substring(0, 1).toUpperCase() + town.substring(1);
        }
        return "";
    }

    /**
     * Simple order info class
     */
    private static class OrderInfo {
        String id;
        String status;

        OrderInfo(String id, String status) {
            this.id = id;
            this.status = status;
        }
    }

    public boolean saveInteraction(String interactionType, String userNumber, String userMessage, String quote, String status) {
        return handleCustomerInteraction(userNumber, interactionType, userMessage, quote, status);
    }
}