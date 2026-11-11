package com.example.reports_service.dto;

import com.example.reports_service.messaging.Events.ComponentResult;
import com.example.reports_service.model.Inspection;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.NotificationStatus;
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

    /** Estado de un correo y, si no salio, el motivo. status null = aun no se sabe (inspeccion abierta o anterior al seguimiento). */
    public record NotificationInfo(NotificationStatus status, String error) {
    }

    /** confirmation: correo de "tu pieza paso la revision". report: generar el PDF y enviarlo. */
    public record Notifications(NotificationInfo confirmation, NotificationInfo report) {
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
            Instant createdAt,
            Notifications notifications) {

        public static InspectionResponse from(Inspection i) {
            NotificationStatus confirmation = i.getConfirmationStatus() != null ? i.getConfirmationStatus()
                    : (i.getConfirmationEmailedAt() != null ? NotificationStatus.SENT : null);
            Notifications notifications = new Notifications(
                    new NotificationInfo(confirmation, i.getConfirmationError()),
                    new NotificationInfo(i.getReportStatus(), i.getReportError()));
            return new InspectionResponse(i.getId(), i.getOrderId(), i.getOrderItemId(), i.getPublicationId(),
                    i.getDescription(), i.getBuyerId(), i.getBuyerEmail(), i.getSellerId(), i.visibleStatus(),
                    i.getAgentId(), i.getMaintenanceDone(), i.getMaintenanceDate(), i.getWorkshopNotes(),
                    i.getChecks().stream().map(c -> new CheckResponse(c.getComponent(), c.getResult(), c.getNote())).toList(),
                    i.getCompletedAt(), i.getDeliveredAt(), i.getCreatedAt(), notifications);
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
                    i.getDescription(), i.getBuyerId(), i.visibleStatus(), i.getCompletedAt(), i.getDeliveredAt(),
                    i.getCreatedAt());
        }
    }
}
