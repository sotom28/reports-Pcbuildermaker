package com.example.reports_service.messaging;

import com.example.reports_service.model.InspectionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Payloads JSON que viajan por RabbitMQ. Solo IDs y datos para armar el PDF y los correos, sin llamadas de vuelta. */
public final class Events {

    private Events() {
    }

    /** Entrante: payments publica esto cuando el pago queda confirmado. Un item = una pieza a revisar. */
    public record OrderPaid(
            String orderId,
            String buyerId,
            String buyerEmail,
            Instant timestamp,
            List<PaidItem> items) {
    }

    public record PaidItem(
            String orderItemId,
            String publicationId,
            String sellerId,
            String description,
            Integer price) {
    }

    /** Resultado de revisar un componente (CPU, GPU, fuente...). */
    public record ComponentCheck(String component, ComponentResult result, String note) {
    }

    public enum ComponentResult { OK, WARNING, FAIL }

    /** Saliente: el admin envio el formulario. Lo consumen la cola de confirmacion y la de generacion del PDF. */
    public record InspectionCompleted(
            String eventType,
            Instant timestamp,
            String inspectionId,
            String orderId,
            String orderItemId,
            String publicationId,
            String description,
            String sellerId,
            String buyerId,
            String buyerEmail,
            InspectionStatus inspectionStatus,
            String maintenanceDone,
            LocalDate maintenanceDate,
            String workshopNotes,
            String inspectedBy,
            List<ComponentCheck> checks) {
    }

    /** Saliente: el PDF ya existe. Lo consume la cola que lo envia por correo. */
    public record ReportGenerated(
            String eventType,
            Instant timestamp,
            String reportId,
            String inspectionId,
            String orderId,
            String orderItemId,
            String description,
            String buyerEmail,
            InspectionStatus inspectionStatus) {
    }
}
