package com.example.reports_service.messaging;

import com.example.reports_service.model.InspectionStatus;

import java.time.Instant;
import java.util.List;

/** Payloads JSON que viajan por RabbitMQ. Solo IDs y datos para armar el PDF, sin llamadas de vuelta. */
public final class Events {

    private Events() {
    }

    /** Entrante: payments publica esto cuando el pago queda retenido (escrow). Un item = una pieza a revisar. */
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
            String sellerEmail,
            String description,
            Integer price) {
    }

    /** Mensaje temporizador: vuelve a este servicio al vencer el plazo. */
    public record DeadlineReached(String inspectionId) {
    }

    /** Resultado de revisar un componente (CPU, GPU, fuente...). */
    public record ComponentCheck(String component, ComponentResult result, String note) {
    }

    public enum ComponentResult { OK, WARNING, FAIL }

    /** Saliente: el agente termino la revision. Lo consume la cola de informes (PDF + correo) y otros servicios. */
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
            String sellerEmail,
            String buyerEmail,
            InspectionStatus inspectionStatus,
            String workshopNotes,
            String inspectedBy,
            List<ComponentCheck> checks,
            Instant dueAt,
            boolean late) {
    }

    /** Saliente: paso el plazo sin revision. Genera el PDF de aviso y el correo al cliente. */
    public record InspectionDelayed(
            String eventType,
            Instant timestamp,
            String inspectionId,
            String orderId,
            String orderItemId,
            String description,
            String sellerId,
            String buyerId,
            String sellerEmail,
            String buyerEmail,
            Instant dueAt) {
    }
}
