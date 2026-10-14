package com.example.reports_service;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.dto.InspectionDtos.CheckRequest;
import com.example.reports_service.dto.InspectionDtos.CompleteRequest;
import com.example.reports_service.exception.ApiExceptions.ConflictException;
import com.example.reports_service.exception.ApiExceptions.ForbiddenException;
import com.example.reports_service.messaging.Events.ComponentResult;
import com.example.reports_service.messaging.Events.DeadlineReached;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.InspectionDelayed;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.messaging.Events.PaidItem;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.Report;
import com.example.reports_service.service.InspectionService;
import com.example.reports_service.service.ReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class InspectionFlowTest {

    @Autowired
    InspectionService service;

    @Autowired
    ReportService reports;

    @MockitoBean
    RabbitTemplate rabbit;

    static final AuthContext AGENT = new AuthContext("agent-1", AuthContext.ROLE_WORKSHOP_ADMIN);
    static final AuthContext BUYER = new AuthContext("buyer-1", null);

    OrderPaid order(String n) {
        return new OrderPaid("order-" + n, "buyer-1", "comprador@correo.cl", Instant.now(),
                List.of(new PaidItem("item-" + n, "pub-" + n, "seller-1", "vendedor@correo.cl", "RTX 4070 usada", 450000)));
    }

    String openId(String n) {
        return service.listOpen(AGENT).stream().filter(i -> i.orderItemId().equals("item-" + n)).findFirst().orElseThrow().id();
    }

    @Test
    void pagoCreaInspeccionPendienteYArrancaElPlazo() {
        service.registerPaidOrder(order("1"));
        service.registerPaidOrder(order("1")); // duplicado: se ignora

        var open = service.listOpen(AGENT).stream().filter(i -> i.orderItemId().equals("item-1")).toList();
        assertEquals(1, open.size());
        assertEquals(InspectionStatus.PENDING, open.get(0).status());
        assertFalse(open.get(0).overdue());

        // un unico mensaje temporizador, publicado en el exchange de espera
        verify(rabbit).convertAndSend(eq(RabbitConfig.DELAY_EXCHANGE), eq(RabbitConfig.KEY_WAIT), any(DeadlineReached.class));
    }

    @Test
    void agenteCompletaConDetallePorComponenteYSeGeneraElPdf() throws Exception {
        service.registerPaidOrder(order("2"));
        String id = openId("2");

        service.claim(AGENT, id);
        service.complete(AGENT, id, new CompleteRequest(InspectionStatus.PASSED, "Pasta termica renovada",
                List.of(new CheckRequest("GPU RTX 4070", ComponentResult.OK, "Sin artefactos en 1 h de estres"),
                        new CheckRequest("Ventiladores", ComponentResult.WARNING, "Uno con ruido leve"))));

        var captor = ArgumentCaptor.forClass(InspectionCompleted.class);
        verify(rabbit).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq(RabbitConfig.KEY_COMPLETED), captor.capture());
        InspectionCompleted event = captor.getValue();
        assertEquals(InspectionStatus.PASSED, event.inspectionStatus());
        assertEquals(2, event.checks().size());
        assertEquals("comprador@correo.cl", event.buyerEmail());
        assertFalse(event.late());

        // lo que haria el listener de reports.technical-review
        reports.onCompleted(event);
        reports.onCompleted(event); // reentrega: no duplica
        Report report = reports.latestTechnicalReport("item-2");
        byte[] pdf = Files.readAllBytes(Path.of(report.getPdfUrl()));
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF"), "debe ser un PDF valido");

        // cerrada: no se puede volver a completar
        assertThrows(ConflictException.class,
                () -> service.complete(AGENT, id, new CompleteRequest(InspectionStatus.FAILED, null, null)));
    }

    @Test
    void vencidoElPlazoSeAvisaUnaSolaVezYLaInspeccionSigueAbierta() throws Exception {
        service.registerPaidOrder(order("3"));
        String id = openId("3");

        service.onDeadline(id);
        service.onDeadline(id); // reentrega del mismo mensaje: no duplica el aviso

        var captor = ArgumentCaptor.forClass(InspectionDelayed.class);
        verify(rabbit).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq(RabbitConfig.KEY_DELAYED), captor.capture());
        assertEquals("vendedor@correo.cl", captor.getValue().sellerEmail());
        assertTrue(service.get(AGENT, id).status().isOpen(), "sigue abierta: tiene mas tiempo");

        reports.onDelayed(captor.getValue());
        byte[] pdf = Files.readAllBytes(Path.of("target/test-reports-pdf/delay_notice-item-3.pdf"));
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF"));
    }

    @Test
    void inspeccionCerradaNoGeneraAvisoDeRetraso() {
        service.registerPaidOrder(order("4"));
        String id = openId("4");
        service.complete(AGENT, id, new CompleteRequest(InspectionStatus.FAILED, "Placa danada", null));

        service.onDeadline(id);

        verify(rabbit, never()).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq(RabbitConfig.KEY_DELAYED), any(InspectionDelayed.class));
    }

    @Test
    void soloElAgenteOperaLaBandeja() {
        assertThrows(ForbiddenException.class, () -> service.listOpen(BUYER));
    }
}
