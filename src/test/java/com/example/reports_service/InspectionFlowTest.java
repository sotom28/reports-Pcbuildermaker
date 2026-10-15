package com.example.reports_service;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.dto.InspectionDtos.CheckRequest;
import com.example.reports_service.dto.InspectionDtos.CompleteRequest;
import com.example.reports_service.exception.ApiExceptions.ConflictException;
import com.example.reports_service.exception.ApiExceptions.ForbiddenException;
import com.example.reports_service.mail.MailService;
import com.example.reports_service.messaging.Events.ComponentResult;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.messaging.Events.PaidItem;
import com.example.reports_service.messaging.Events.ReportGenerated;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.Report;
import com.example.reports_service.service.InspectionService;
import com.example.reports_service.service.ReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class InspectionFlowTest {

    @Autowired
    InspectionService service;

    @Autowired
    ReportService reports;

    @MockitoBean
    RabbitTemplate rabbit;

    @MockitoBean
    MailService mail;

    static final AuthContext ADMIN = new AuthContext("admin-1", AuthContext.ROLE_WORKSHOP_ADMIN);
    static final AuthContext BUYER = new AuthContext("buyer-1", null);

    OrderPaid order(String n) {
        return new OrderPaid("order-" + n, "buyer-1", "comprador@correo.cl", Instant.now(),
                List.of(new PaidItem("item-" + n, "pub-" + n, "seller-1", "RTX 4070 usada", 450000)));
    }

    String openId(String n) {
        return service.listOpen(ADMIN).stream().filter(i -> i.orderItemId().equals("item-" + n)).findFirst().orElseThrow().id();
    }

    CompleteRequest form(InspectionStatus result) {
        return new CompleteRequest(result, "Limpieza y pasta térmica renovada", LocalDate.of(2026, 10, 14),
                "Todo en orden", List.of(
                        new CheckRequest("GPU RTX 4070", ComponentResult.OK, "Sin artefactos en 1 h de estrés"),
                        new CheckRequest("Ventiladores", ComponentResult.WARNING, "Uno con ruido leve")));
    }

    <T> T published(String key, Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(key), captor.capture(), any(CorrelationData.class));
        return type.cast(captor.getValue());
    }

    @Test
    void pagoCreaInspeccionPendienteEnLaBandejaDelAdmin() {
        service.registerPaidOrder(order("1"));
        service.registerPaidOrder(order("1")); // duplicado: se ignora

        var open = service.listOpen(ADMIN).stream().filter(i -> i.orderItemId().equals("item-1")).toList();
        assertEquals(1, open.size());
        assertEquals(InspectionStatus.PENDING, open.get(0).status());
        assertEquals("comprador@correo.cl", open.get(0).buyerEmail());
        // ya no hay temporizador ni mensajes salientes al registrar el pago
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    void formularioDisparaConfirmacionYPdfYElPdfDisparaElCorreoConAdjunto() throws Exception {
        service.registerPaidOrder(order("2"));
        String id = openId("2");

        // el admin envia el formulario directo desde PENDING (sin claim)
        service.complete(ADMIN, id, form(InspectionStatus.PASSED));
        InspectionCompleted completed = published(RabbitConfig.KEY_COMPLETED, InspectionCompleted.class);
        assertEquals(InspectionStatus.PASSED, completed.inspectionStatus());
        assertEquals("Limpieza y pasta térmica renovada", completed.maintenanceDone());
        assertEquals(LocalDate.of(2026, 10, 14), completed.maintenanceDate());
        assertEquals("comprador@correo.cl", completed.buyerEmail());
        assertEquals(2, completed.checks().size());

        // cola mail.confirmation: un correo sin adjunto, y la reentrega no lo duplica
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(true);
        reports.sendConfirmation(completed);
        reports.sendConfirmation(completed);
        verify(mail, times(1)).send(eq(List.of("comprador@correo.cl")), anyString(), anyString());

        // cola report.generate: crea el PDF y anuncia report.generated
        reports.generate(completed);
        Report report = reports.latestTechnicalReport("item-2");
        byte[] pdf = Files.readAllBytes(Path.of(report.getPdfUrl()));
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF"), "debe ser un PDF valido");
        ReportGenerated generated = published(RabbitConfig.KEY_REPORT_GENERATED, ReportGenerated.class);
        assertEquals(report.getReportId(), generated.reportId());

        // cola mail.report: envia el PDF adjunto, y la reentrega no lo duplica
        when(mail.sendPdf(anyList(), anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(true);
        reports.mailReport(generated);
        reports.mailReport(generated);
        var attachment = ArgumentCaptor.forClass(byte[].class);
        verify(mail, times(1)).sendPdf(eq(List.of("comprador@correo.cl")), anyString(), anyString(),
                eq("informe-tecnico-item-2.pdf"), attachment.capture());
        assertTrue(new String(attachment.getValue(), 0, 5).startsWith("%PDF"));

        // cerrada: no se puede volver a completar
        assertThrows(ConflictException.class, () -> service.complete(ADMIN, id, form(InspectionStatus.FAILED)));
    }

    @Test
    void reentregaDelFormularioNoDuplicaElPdfPeroLoReanunciaSiNadieLoEnvio() throws Exception {
        service.registerPaidOrder(order("3"));
        String id = openId("3");
        service.complete(ADMIN, id, form(InspectionStatus.PASSED));
        InspectionCompleted completed = published(RabbitConfig.KEY_COMPLETED, InspectionCompleted.class);

        reports.generate(completed);
        reports.generate(completed); // reentrega: mismo PDF, se vuelve a anunciar porque aun no se envia
        assertEquals(1, reports.reportCount("item-3"), "un solo PDF por item");
        verify(rabbit, times(2)).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.KEY_REPORT_GENERATED),
                any(Object.class), any(CorrelationData.class));

        // una vez enviado por correo, la reentrega ya no lo reanuncia
        ReportGenerated generated = new ReportGenerated(RabbitConfig.KEY_REPORT_GENERATED, Instant.now(),
                reports.latestTechnicalReport("item-3").getReportId(), completed.inspectionId(), completed.orderId(),
                completed.orderItemId(), completed.description(), completed.buyerEmail(), completed.inspectionStatus());
        when(mail.sendPdf(anyList(), anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(true);
        reports.mailReport(generated);
        reports.generate(completed);
        verify(rabbit, times(2)).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.KEY_REPORT_GENERATED),
                any(Object.class), any(CorrelationData.class));
    }

    @Test
    void rechazadaTambienGeneraConfirmacionYPdf() throws Exception {
        service.registerPaidOrder(order("4"));
        String id = openId("4");
        service.complete(ADMIN, id, new CompleteRequest(InspectionStatus.FAILED, "Placa dañada", null, null, null));

        InspectionCompleted completed = published(RabbitConfig.KEY_COMPLETED, InspectionCompleted.class);
        assertEquals(InspectionStatus.FAILED, completed.inspectionStatus());
        assertNotNull(completed.maintenanceDate(), "sin fecha en el formulario se usa hoy");
        reports.generate(completed);
        byte[] pdf = Files.readAllBytes(Path.of(reports.latestTechnicalReport("item-4").getPdfUrl()));
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF"));
    }

    @Test
    void sinSmtpLaConfirmacionNoSeMarcaComoEnviada() {
        service.registerPaidOrder(order("5"));
        String id = openId("5");
        service.complete(ADMIN, id, form(InspectionStatus.PASSED));
        InspectionCompleted completed = published(RabbitConfig.KEY_COMPLETED, InspectionCompleted.class);

        when(mail.send(anyList(), anyString(), anyString())).thenReturn(false); // SMTP sin configurar
        reports.sendConfirmation(completed);
        reports.sendConfirmation(completed);
        verify(mail, times(2)).send(anyList(), anyString(), anyString()); // se vuelve a intentar
        assertFalse(service.isConfirmationEmailed(id));
    }

    @Test
    void cadaCompradorVeSoloSusCompras() {
        service.registerPaidOrder(order("6")); // comprador buyer-1
        service.registerPaidOrder(new OrderPaid("order-7", "buyer-2", "otro@correo.cl", Instant.now(),
                List.of(new PaidItem("item-7", "pub-7", "seller-1", "SSD 1TB", 60000))));

        var mine = service.listAsBuyer(BUYER);
        assertTrue(mine.stream().anyMatch(i -> i.orderItemId().equals("item-6")));
        assertTrue(mine.stream().noneMatch(i -> i.orderItemId().equals("item-7")), "no ve compras ajenas");
        assertTrue(service.listAsBuyer(new AuthContext("buyer-3", null)).isEmpty());
    }

    @Test
    void soloElAdminOperaLaBandeja() {
        assertThrows(ForbiddenException.class, () -> service.listOpen(BUYER));
    }
}
