package com.example.reports_service.dto;

import com.example.reports_service.messaging.Events.ComponentResult;
import com.example.reports_service.model.Inspection;
import com.example.reports_service.model.InspectionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record CheckRequest(
            @NotBlank @Size(max = 100) String component,
            @NotNull ComponentResult result,
            @Size(max = 255) String note) {
    }

    /** result: PASSED o FAILED. checks: estado de cada componente revisado (aparece en el PDF). */
    public record CompleteRequest(
            @NotNull InspectionStatus result,
            @Size(max = 1000) String notes,
            @Valid List<CheckRequest> checks) {
    }

    public record CheckResponse(String component, ComponentResult result, String note) {
    }

    public record InspectionResponse(
            String id,
            String orderId,
            String orderItemId,
            String publicationId,
            String description,
            String buyerId,
            String sellerId,
            InspectionStatus status,
            String agentId,
            String workshopNotes,
            List<CheckResponse> checks,
            Instant dueAt,
            boolean overdue,
            Instant completedAt,
            Instant createdAt) {

        public static InspectionResponse from(Inspection i) {
            boolean overdue = i.getStatus().isOpen() && Instant.now().isAfter(i.getDueAt());
            return new InspectionResponse(i.getId(), i.getOrderId(), i.getOrderItemId(), i.getPublicationId(),
                    i.getDescription(), i.getBuyerId(), i.getSellerId(), i.getStatus(), i.getAgentId(),
                    i.getWorkshopNotes(),
                    i.getChecks().stream().map(c -> new CheckResponse(c.getComponent(), c.getResult(), c.getNote())).toList(),
                    i.getDueAt(), overdue, i.getCompletedAt(), i.getCreatedAt());
        }
    }
}
