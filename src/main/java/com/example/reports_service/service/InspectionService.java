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

/** El admin del taller revisa cada pieza pagada ANTES de enviarla; de esa revision sale el informe. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InspectionService {

    private static final ZoneId WORKSHOP_ZONE = ZoneId.of("America/Santiago");

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
                .stream().map(InspectionResponse::from).toList();
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
        return inspections.findByStatusAndDeliveredAtIsNullOrderByCompletedAtAsc(InspectionStatus.PASSED)
                .stream().map(InspectionResponse::from).toList();
    }

    /**
     * El taller entrega la pieza aprobada al comprador. Solo aplica a PASSED. Es IDEMPOTENTE: si ya estaba entregada
     * responde con el mismo estado (permite que el BFF reintente el paso de actualizar la publicacion).
     */
    public InspectionResponse deliver(AuthContext auth, String id) {
        auth.requireAgent();
        Inspection i = load(id);
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
        if (i.getStatus() != InspectionStatus.PENDING) {
            throw new ConflictException("La inspeccion no esta pendiente (estado " + i.getStatus() + ")");
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
        if (!i.getStatus().isOpen()) {
            throw new ConflictException("La inspeccion ya fue cerrada (" + i.getStatus() + ")");
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

        events.publishEvent(new OutboundMessage(RabbitConfig.EXCHANGE, RabbitConfig.KEY_COMPLETED,
                new InspectionCompleted(RabbitConfig.KEY_COMPLETED, now, i.getId(), i.getOrderId(), i.getOrderItemId(),
                        i.getPublicationId(), i.getDescription(), i.getSellerId(), i.getBuyerId(), i.getBuyerEmail(),
                        i.getStatus(), i.getMaintenanceDone(), i.getMaintenanceDate(), i.getWorkshopNotes(),
                        i.getAgentId(),
                        checks.stream().map(c -> new ComponentCheck(c.component(), c.result(), c.note())).toList())));
        return InspectionResponse.from(i);
    }

    /** Marca el correo de confirmacion como enviado (evita duplicarlo si RabbitMQ reentrega el mensaje). */
    public void markConfirmationEmailed(String inspectionId) {
        load(inspectionId).setConfirmationEmailedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public boolean isConfirmationEmailed(String inspectionId) {
        return load(inspectionId).getConfirmationEmailedAt() != null;
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
