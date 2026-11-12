package com.example.reports_service.service;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.dto.InspectionDtos.CheckRequest;
import com.example.reports_service.dto.InspectionDtos.CompleteRequest;
import com.example.reports_service.dto.InspectionDtos.InspectionResponse;
import com.example.reports_service.dto.InspectionDtos.SaleResponse;
import com.example.reports_service.exception.ApiExceptions.ConflictException;
import com.example.reports_service.exception.ApiExceptions.NotFoundException;
import com.example.reports_service.messaging.Events.ComponentCheck;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.messaging.Events.PaidItem;
import com.example.reports_service.messaging.OutboundMessage;
import com.example.reports_service.model.Inspection;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.NotificationStatus;
import com.example.reports_service.repository.InspectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** El admin del taller revisa cada pieza pagada ANTES de enviarla; de esa revision sale el informe. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InspectionService {

    private static final ZoneId WORKSHOP_ZONE = ZoneId.of("America/Santiago");
    /** Un correo que lleva mas que esto en PENDING se considera atascado (mensaje perdido o consumidor caido). */
    private static final java.time.Duration STUCK_AFTER = java.time.Duration.ofMinutes(2);

    private final InspectionRepository inspections;
    private final ApplicationEventPublisher events;

    /** Pago confirmado: crea una inspeccion pendiente por item (aparece sola en la bandeja del admin). Idempotente. */
    public void registerPaidOrder(OrderPaid order) {
        for (PaidItem item : order.items()) {
            if (inspections.existsByOrderItemId(item.orderItemId())) {
                log.info("Inspeccion ya registrada para el item {}, se ignora el duplicado", item.orderItemId());
                continue;
            }
            Inspection i = new Inspection();
            i.setOrderItemId(item.orderItemId());
            i.setOrderId(order.orderId());
            i.setPublicationId(item.publicationId());
            i.setDescription(item.description());
            i.setBuyerId(order.buyerId());
            i.setSellerId(item.sellerId());
            i.setBuyerEmail(order.buyerEmail());
            inspections.save(i);
            log.info("Inspeccion {} creada para el item {} (orden {})", i.getId(), item.orderItemId(), order.orderId());
        }
    }

    @Transactional(readOnly = true)
    public List<InspectionResponse> listOpen(AuthContext auth) {
        auth.requireAgent();
        return inspections.findByStatusInOrderByCreatedAtAsc(EnumSet.of(InspectionStatus.PENDING, InspectionStatus.IN_REVIEW))
                .stream().filter(i -> i.getCancelledAt() == null).map(InspectionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<InspectionResponse> listMine(AuthContext auth) {
        auth.requireAgent();
        return inspections.findByAgentIdOrderByCreatedAtDesc(auth.userId())
                .stream().map(InspectionResponse::from).toList();
    }

    /** Las inspecciones de las piezas que compro el usuario (no requiere ser admin). */
    @Transactional(readOnly = true)
    public List<InspectionResponse> listAsBuyer(AuthContext auth) {
        return inspections.findByBuyerIdOrderByCreatedAtDesc(auth.userId())
                .stream().map(InspectionResponse::from).toList();
    }

    /** Las ventas del usuario (no requiere ser admin): solo las piezas que EL vendio, en un formato reducido. */
    @Transactional(readOnly = true)
    public List<SaleResponse> listAsSeller(AuthContext auth) {
        return inspections.findBySellerIdOrderByCreatedAtDesc(auth.userId())
                .stream().map(SaleResponse::from).toList();
    }

    /** Aprobadas pendientes de entrega (pestana "Por entregar" del admin). */
    @Transactional(readOnly = true)
    public List<InspectionResponse> listToDeliver(AuthContext auth) {
        auth.requireAgent();
        return inspections.findByStatusAndDeliveredAtIsNullAndCancelledAtIsNullOrderByCompletedAtAsc(InspectionStatus.PASSED)
                .stream().map(InspectionResponse::from).toList();
    }

    /**
     * El taller entrega la pieza aprobada al comprador. Solo aplica a PASSED. Es IDEMPOTENTE: si ya estaba entregada
     * responde con el mismo estado (permite que el BFF reintente el paso de actualizar la publicacion).
     */
    public InspectionResponse deliver(AuthContext auth, String id) {
        auth.requireAgent();
        Inspection i = load(id);
        if (i.getCancelledAt() != null) {
            throw new ConflictException("La compra fue cancelada: no se puede entregar");
        }
        if (i.getStatus() != InspectionStatus.PASSED) {
            throw new ConflictException("Solo se puede entregar una pieza aprobada (estado " + i.getStatus() + ")");
        }
        if (i.getDeliveredAt() == null) {
            // Milisegundos: la primera respuesta debe coincidir con lo que luego se lee de la base (que redondea).
            i.setDeliveredAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        }
        return InspectionResponse.from(i);
    }

    @Transactional(readOnly = true)
    public InspectionResponse get(AuthContext auth, String id) {
        Inspection i = load(id);
        requireParticipant(auth, i);
        return InspectionResponse.from(i);
    }

    /** Participantes de la inspeccion (comprador, vendedor) y admins; el resto recibe 404. */
    @Transactional(readOnly = true)
    public Inspection requireParticipantByItem(AuthContext auth, String orderItemId) {
        Inspection i = inspections.findByOrderItemId(orderItemId)
                .orElseThrow(() -> new NotFoundException("Item no encontrado: " + orderItemId));
        requireParticipant(auth, i);
        return i;
    }

    /** El admin toma la revision (opcional: complete tambien funciona desde PENDING). */
    public InspectionResponse claim(AuthContext auth, String id) {
        auth.requireAgent();
        Inspection i = load(id);
        if (i.getCancelledAt() != null || i.getStatus() != InspectionStatus.PENDING) {
            throw new ConflictException("La inspeccion no esta pendiente (estado " + i.visibleStatus() + ")");
        }
        i.setStatus(InspectionStatus.IN_REVIEW);
        i.setAgentId(auth.userId());
        return InspectionResponse.from(i);
    }

    /** El admin envia el formulario: aprobada o rechazada. Dispara confirmacion por correo + PDF. */
    public InspectionResponse complete(AuthContext auth, String id, CompleteRequest request) {
        auth.requireAgent();
        if (request.result() != InspectionStatus.PASSED && request.result() != InspectionStatus.FAILED) {
            throw new IllegalArgumentException("El resultado debe ser PASSED o FAILED");
        }
        Inspection i = load(id);
        if (i.getCancelledAt() != null || !i.getStatus().isOpen()) {
            throw new ConflictException("La inspeccion ya fue cerrada (" + i.visibleStatus() + ")");
        }
        if (i.getStatus() == InspectionStatus.IN_REVIEW && !auth.userId().equals(i.getAgentId())) {
            throw new ConflictException("La inspeccion la tiene otro agente");
        }
        Instant now = Instant.now();
        i.setStatus(request.result());
        i.setAgentId(auth.userId());
        i.setMaintenanceDone(request.maintenanceDone());
        i.setMaintenanceDate(request.maintenanceDate() != null ? request.maintenanceDate() : LocalDate.now(WORKSHOP_ZONE));
        i.setWorkshopNotes(request.notes());
        i.setCompletedAt(now);
        i.getChecks().clear();
        List<CheckRequest> checks = request.checks() == null ? List.of() : request.checks();
        checks.forEach(c -> i.getChecks().add(new Inspection.CheckEntry(c.component(), c.result(), c.note())));
        i.setConfirmationStatus(NotificationStatus.PENDING);
        i.setConfirmationError(null);
        i.setReportStatus(NotificationStatus.PENDING);
        i.setReportError(null);

        events.publishEvent(completedMessage(i, now));
        return InspectionResponse.from(i);
    }

    /**
     * El taller cancela la compra (p. ej. no se puede avisar al comprador): la pieza deja de estar en la bandeja, en
     * "Por entregar" y en "Correos". El BFF reembolsa la orden y devuelve la publicacion al catalogo. Idempotente.
     * No aplica a una pieza ya rechazada (ya se reembolso) ni a una ya entregada.
     */
    public InspectionResponse cancel(AuthContext auth, String id) {
        auth.requireAgent();
        Inspection i = load(id);
        if (i.getCancelledAt() != null) {
            return InspectionResponse.from(i);
        }
        if (i.getStatus() == InspectionStatus.FAILED) {
            throw new ConflictException("La pieza ya fue rechazada y su compra reembolsada: no hay nada que cancelar");
        }
        if (i.getDeliveredAt() != null) {
            throw new ConflictException("La pieza ya fue entregada: no se puede cancelar la compra");
        }
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        i.setCancelledAt(now);
        if (i.getCompletedAt() == null) {
            i.setCompletedAt(now);
        }
        i.setAgentId(auth.userId());
        return InspectionResponse.from(i);
    }

    /** Correo de confirmacion enviado (evita duplicarlo si RabbitMQ reentrega el mensaje). */
    public void markConfirmationSent(String inspectionId) {
        Inspection i = load(inspectionId);
        i.setConfirmationEmailedAt(Instant.now());
        i.setConfirmationStatus(NotificationStatus.SENT);
        i.setConfirmationError(null);
    }

    @Transactional(readOnly = true)
    public boolean isConfirmationEmailed(String inspectionId) {
        return load(inspectionId).getConfirmationEmailedAt() != null;
    }

    /** Deja constancia del estado del correo de confirmacion (no enviado, fallido...) en vez de perderlo en un log. */
    public void recordConfirmation(String inspectionId, NotificationStatus status, String error) {
        inspections.findById(inspectionId).ifPresent(i -> {
            i.setConfirmationStatus(status);
            i.setConfirmationError(truncate(error));
        });
    }

    /** Ídem para el informe (generar el PDF y enviarlo). */
    public void recordReport(String inspectionId, NotificationStatus status, String error) {
        inspections.findById(inspectionId).ifPresent(i -> {
            i.setReportStatus(status);
            i.setReportError(truncate(error));
        });
    }

    /**
     * Inspecciones cerradas cuyos correos necesitan atencion (pestana "Correos" del admin): alguno no se envio o fallo, o
     * lleva demasiado en pendiente. Las anteriores al seguimiento (sin estado) no se consideran.
     */
    @Transactional(readOnly = true)
    public List<InspectionResponse> listNotificationProblems(AuthContext auth) {
        auth.requireAgent();
        Instant stuckBefore = Instant.now().minus(STUCK_AFTER);
        return inspections.findByStatusInOrderByCompletedAtDesc(Set.of(InspectionStatus.PASSED, InspectionStatus.FAILED))
                .stream()
                .filter(i -> i.getCancelledAt() == null && i.getNotificationsDismissedAt() == null)
                .filter(i -> needsAttention(i.getConfirmationStatus(), i.getCompletedAt(), stuckBefore)
                        || needsAttention(i.getReportStatus(), i.getCompletedAt(), stuckBefore))
                .map(InspectionResponse::from)
                .toList();
    }

    /**
     * El admin pide reenviar los correos de una inspeccion cerrada. Vuelve a publicar inspection.completed: los consumidores
     * son idempotentes, asi que lo que ya se envio no se repite y solo se rehace lo pendiente o fallido.
     */
    public InspectionResponse resendNotifications(AuthContext auth, String id) {
        auth.requireAgent();
        Inspection i = load(id);
        if (i.getCancelledAt() != null) {
            throw new ConflictException("La compra fue cancelada: no hay correos que reenviar");
        }
        if (i.getStatus().isOpen()) {
            throw new ConflictException("La inspeccion aun no esta cerrada: no hay correos que reenviar");
        }
        boolean confirmationDone = i.getConfirmationEmailedAt() != null;
        boolean reportDone = i.getReportStatus() == NotificationStatus.SENT;
        if (confirmationDone && reportDone) {
            return InspectionResponse.from(i); // nada pendiente
        }
        i.setNotificationsDismissedAt(null); // el admin vuelve a ocuparse de esto: vuelve a la lista si algo falla
        if (!confirmationDone) {
            i.setConfirmationStatus(NotificationStatus.PENDING);
            i.setConfirmationError(null);
        }
        if (!reportDone) {
            i.setReportStatus(NotificationStatus.PENDING);
            i.setReportError(null);
        }
        events.publishEvent(completedMessage(i, Instant.now()));
        return InspectionResponse.from(i);
    }

    /**
     * El admin da por revisado el aviso de correos de una inspeccion cerrada y la saca de la pestana "Correos" sin reenviar nada
     * (para lo que reenviar no arregla, como un comprador sin correo). No toca los estados: siguen visibles en el detalle. Idempotente.
     */
    public InspectionResponse dismissNotifications(AuthContext auth, String id) {
        auth.requireAgent();
        Inspection i = load(id);
        if (i.getStatus().isOpen() && i.getCancelledAt() == null) {
            throw new ConflictException("La inspeccion aun no esta cerrada: no hay avisos que descartar");
        }
        if (i.getNotificationsDismissedAt() == null) {
            i.setNotificationsDismissedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        }
        return InspectionResponse.from(i);
    }

    private static boolean needsAttention(NotificationStatus status, Instant completedAt, Instant stuckBefore) {
        if (status == null || status == NotificationStatus.SENT) {
            return false;
        }
        if (status == NotificationStatus.PENDING) {
            return completedAt != null && completedAt.isBefore(stuckBefore);
        }
        return true; // NOT_SENT o FAILED
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    private OutboundMessage completedMessage(Inspection i, Instant at) {
        List<ComponentCheck> checks = i.getChecks().stream()
                .map(c -> new ComponentCheck(c.getComponent(), c.getResult(), c.getNote())).toList();
        return new OutboundMessage(RabbitConfig.EXCHANGE, RabbitConfig.KEY_COMPLETED,
                new InspectionCompleted(RabbitConfig.KEY_COMPLETED, at, i.getId(), i.getOrderId(), i.getOrderItemId(),
                        i.getPublicationId(), i.getDescription(), i.getSellerId(), i.getBuyerId(), i.getBuyerEmail(),
                        i.getStatus(), i.getMaintenanceDone(), i.getMaintenanceDate(), i.getWorkshopNotes(),
                        i.getAgentId(), checks));
    }

    private Inspection load(String id) {
        return inspections.findById(id).orElseThrow(() -> new NotFoundException("Inspeccion no encontrada: " + id));
    }

    private void requireParticipant(AuthContext auth, Inspection i) {
        boolean allowed = auth.isAgent()
                || auth.userId().equals(i.getBuyerId())
                || auth.userId().equals(i.getSellerId());
        if (!allowed) {
            throw new NotFoundException("Inspeccion no encontrada"); // 404, no revela que existe
        }
    }
}
