package com.example.reports_service.service;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.dto.InspectionDtos.CheckRequest;
import com.example.reports_service.dto.InspectionDtos.CompleteRequest;
import com.example.reports_service.dto.InspectionDtos.InspectionResponse;
import com.example.reports_service.exception.ApiExceptions.ConflictException;
import com.example.reports_service.exception.ApiExceptions.NotFoundException;
import com.example.reports_service.messaging.Events.ComponentCheck;
import com.example.reports_service.messaging.Events.DeadlineReached;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.InspectionDelayed;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.messaging.Events.PaidItem;
import com.example.reports_service.messaging.OutboundMessage;
import com.example.reports_service.model.Inspection;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.repository.InspectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** El agente tecnico revisa cada pieza pagada ANTES de enviarla; de esa revision sale el informe. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InspectionService {

    private final InspectionRepository inspections;
    private final ApplicationEventPublisher events;

    @Value("${app.inspection.deadline}")
    private Duration deadline;

    /** Pago retenido: crea una inspeccion pendiente por item y arranca el reloj del plazo. Idempotente. */
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
            i.setSellerEmail(item.sellerEmail());
            i.setDueAt(i.getCreatedAt().plus(deadline));
            inspections.save(i);
            // El TTL de la cola de espera lo devuelve a este servicio cuando se cumple el plazo.
            events.publishEvent(new OutboundMessage(RabbitConfig.DELAY_EXCHANGE, RabbitConfig.KEY_WAIT,
                    new DeadlineReached(i.getId())));
        }
    }

    /** El plazo vencio: si la inspeccion sigue abierta se avisa al cliente, pero no se cancela nada. */
    public void onDeadline(String inspectionId) {
        Inspection i = inspections.findById(inspectionId).orElse(null);
        if (i == null || !i.getStatus().isOpen() || i.getDelayNotifiedAt() != null) {
            return;
        }
        i.setDelayNotifiedAt(Instant.now());
        events.publishEvent(new OutboundMessage(RabbitConfig.EVENTS_EXCHANGE, RabbitConfig.KEY_DELAYED,
                new InspectionDelayed(RabbitConfig.KEY_DELAYED, Instant.now(), i.getId(), i.getOrderId(),
                        i.getOrderItemId(), i.getDescription(), i.getSellerId(), i.getBuyerId(),
                        i.getSellerEmail(), i.getBuyerEmail(), i.getDueAt())));
    }

    @Transactional(readOnly = true)
    public List<InspectionResponse> listOpen(AuthContext auth) {
        auth.requireAgent();
        return inspections.findByStatusInOrderByDueAtAsc(EnumSet.of(InspectionStatus.PENDING, InspectionStatus.IN_REVIEW))
                .stream().map(InspectionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<InspectionResponse> listMine(AuthContext auth) {
        auth.requireAgent();
        return inspections.findByAgentIdOrderByCreatedAtDesc(auth.userId())
                .stream().map(InspectionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public InspectionResponse get(AuthContext auth, String id) {
        Inspection i = load(id);
        requireParticipant(auth, i);
        return InspectionResponse.from(i);
    }

    /** Participantes de la inspeccion (comprador, vendedor) y agentes; el resto recibe 404. */
    @Transactional(readOnly = true)
    public Inspection requireParticipantByItem(AuthContext auth, String orderItemId) {
        Inspection i = inspections.findByOrderItemId(orderItemId)
                .orElseThrow(() -> new NotFoundException("Item no encontrado: " + orderItemId));
        requireParticipant(auth, i);
        return i;
    }

    /** El agente toma la revision. */
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

    /** El agente cierra la revision: aprobada o rechazada. Dispara el informe PDF y el correo. */
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
        i.setWorkshopNotes(request.notes());
        i.setCompletedAt(now);
        i.getChecks().clear();
        List<CheckRequest> checks = request.checks() == null ? List.of() : request.checks();
        checks.forEach(c -> i.getChecks().add(new Inspection.CheckEntry(c.component(), c.result(), c.note())));

        events.publishEvent(new OutboundMessage(RabbitConfig.EVENTS_EXCHANGE, RabbitConfig.KEY_COMPLETED,
                new InspectionCompleted(RabbitConfig.KEY_COMPLETED, now, i.getId(), i.getOrderId(), i.getOrderItemId(),
                        i.getPublicationId(), i.getDescription(), i.getSellerId(), i.getBuyerId(), i.getSellerEmail(),
                        i.getBuyerEmail(), i.getStatus(), i.getWorkshopNotes(), i.getAgentId(),
                        checks.stream().map(c -> new ComponentCheck(c.component(), c.result(), c.note())).toList(),
                        i.getDueAt(), now.isAfter(i.getDueAt()))));
        return InspectionResponse.from(i);
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
