package com.example.reports_service.dto;

import com.example.reports_service.messaging.Events.ComponentResult;
import com.example.reports_service.model.Inspection;
import com.example.reports_service.model.InspectionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record CheckRequest(
            @NotBlank @Size(max = 100) String component,
            @NotNull ComponentResult result,
            @Size(max = 255) String note) {
    }

    /**
     * Formulario del admin. result: PASSED o FAILED. maintenanceDate es opcional (por defecto, hoy).
     * checks es opcional: estado de cada componente revisado, aparece en el PDF si viene.
     */
    public record CompleteRequest(
            @NotNull InspectionStatus result,
            @NotBlank @Size(max = 1000) String maintenanceDone,
            LocalDate maintenanceDate,
            @Size(max = 1000) String notes,
            List<@Valid CheckRequest> checks) {
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
            String buyerEmail,
            String sellerId,
            InspectionStatus status,
            String agentId,
            String maintenanceDone,
            LocalDate maintenanceDate,
            String workshopNotes,
            List<CheckResponse> checks,
            Instant completedAt,
            Instant deliveredAt,
            Instant createdAt) {

        public static InspectionResponse from(Inspection i) {
            return new InspectionResponse(i.getId(), i.getOrderId(), i.getOrderItemId(), i.getPublicationId(),
                    i.getDescription(), i.getBuyerId(), i.getBuyerEmail(), i.getSellerId(), i.getStatus(),
                    i.getAgentId(), i.getMaintenanceDone(), i.getMaintenanceDate(), i.getWorkshopNotes(),
                    i.getChecks().stream().map(c -> new CheckResponse(c.getComponent(), c.getResult(), c.getNote())).toList(),
                    i.getCompletedAt(), i.getDeliveredAt(), i.getCreatedAt());
        }
    }

    /**
     * Lo que ve el VENDEDOR de una venta: sin correo del comprador, sin notas ni detalle del mantenimiento (eso va
     * en el informe PDF, que es del comprador). buyerId existe solo para que el BFF resuelva el nombre publico; el BFF
     * no lo reenvia al front.
     */
    public record SaleResponse(
            String id,
            String orderId,
            String orderItemId,
            String publicationId,
            String description,
            String buyerId,
            InspectionStatus status,
            Instant completedAt,
            Instant deliveredAt,
            Instant createdAt) {

        public static SaleResponse from(Inspection i) {
            return new SaleResponse(i.getId(), i.getOrderId(), i.getOrderItemId(), i.getPublicationId(),
                    i.getDescription(), i.getBuyerId(), i.getStatus(), i.getCompletedAt(), i.getDeliveredAt(),
                    i.getCreatedAt());
        }
    }
}
