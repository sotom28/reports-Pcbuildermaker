package com.example.reports_service;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.dto.InspectionDtos.CheckRequest;
import com.example.reports_service.dto.InspectionDtos.CompleteRequest;
import com.example.reports_service.exception.ApiExceptions.ConflictException;
import com.example.reports_service.exception.ApiExceptions.ForbiddenException;
import com.example.reports_service.mail.MailResult;
import com.example.reports_service.mail.MailService;
import com.example.reports_service.messaging.Events.ComponentResult;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.messaging.Events.PaidItem;
import com.example.reports_service.messaging.Events.ReportGenerated;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.NotificationStatus;
import com.example.reports_service.model.Report;
import com.example.reports_service.service.InspectionService;
import com.example.reports_service.service.ReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
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
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.ok());
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
        when(mail.sendPdf(anyList(), anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(MailResult.ok());
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
        when(mail.sendPdf(anyList(), anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(MailResult.ok());
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

        when(mail.send(anyList(), anyString(), anyString()))
                .thenReturn(MailResult.notSent("El servidor de correo (SMTP) no esta configurado"));
        reports.sendConfirmation(completed);
        reports.sendConfirmation(completed);
        verify(mail, times(2)).send(anyList(), anyString(), anyString()); // se vuelve a intentar
        assertFalse(service.isConfirmationEmailed(id));
        // ya no se descarta en silencio: queda el estado y el motivo
        var confirmation = service.get(ADMIN, id).notifications().confirmation();
        assertEquals(NotificationStatus.NOT_SENT, confirmation.status());
        assertTrue(confirmation.error().contains("SMTP"));
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

    static final AuthContext SELLER = new AuthContext("seller-1", null);

    @Test
    void elAdminEntregaLaPiezaAprobadaYLaOperacionEsIdempotente() {
        service.registerPaidOrder(order("10"));
        String id = openId("10");
        service.complete(ADMIN, id, form(InspectionStatus.PASSED));

        assertTrue(service.listToDeliver(ADMIN).stream().anyMatch(i -> i.orderItemId().equals("item-10")));

        var first = service.deliver(ADMIN, id);
        assertNotNull(first.deliveredAt());
        var again = service.deliver(ADMIN, id); // reintento del BFF: mismo estado, sin error
        assertEquals(first.deliveredAt(), again.deliveredAt());

        assertTrue(service.listToDeliver(ADMIN).stream().noneMatch(i -> i.orderItemId().equals("item-10")),
                "ya entregada: sale de 'por entregar'");
    }

    @Test
    void soloSePuedeEntregarUnaPiezaAprobada() {
        service.registerPaidOrder(order("11"));
        String pendiente = openId("11");
        assertThrows(ConflictException.class, () -> service.deliver(ADMIN, pendiente));

        service.registerPaidOrder(order("12"));
        String rechazada = openId("12");
        service.complete(ADMIN, rechazada, form(InspectionStatus.FAILED));
        assertThrows(ConflictException.class, () -> service.deliver(ADMIN, rechazada));

        assertThrows(ForbiddenException.class, () -> service.deliver(BUYER, rechazada));
        assertThrows(ForbiddenException.class, () -> service.listToDeliver(BUYER));
    }

    @Test
    void elVendedorVeSoloSusVentasEnFormatoReducido() {
        service.registerPaidOrder(order("13")); // vendedor seller-1
        service.registerPaidOrder(new OrderPaid("order-14", "buyer-2", "otro@correo.cl", Instant.now(),
                List.of(new PaidItem("item-14", "pub-14", "seller-2", "SSD 1TB", 60000))));

        var mias = service.listAsSeller(SELLER);
        assertTrue(mias.stream().anyMatch(v -> v.orderItemId().equals("item-13")));
        assertTrue(mias.stream().noneMatch(v -> v.orderItemId().equals("item-14")), "no ve ventas ajenas");
        var venta = mias.stream().filter(v -> v.orderItemId().equals("item-13")).findFirst().orElseThrow();
        assertEquals("buyer-1", venta.buyerId()); // el BFF lo usa para resolver el nombre y no lo reenvia
        assertEquals(InspectionStatus.PENDING, venta.status());
        assertTrue(service.listAsSeller(new AuthContext("seller-9", null)).isEmpty());
        // SaleResponse no tiene campo de correo ni de notas: lo garantiza el tipo
        assertEquals(10, venta.getClass().getRecordComponents().length);
        assertTrue(java.util.Arrays.stream(venta.getClass().getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("email") || c.getName().toLowerCase().contains("notes")));
    }

    @Test
    void soloElAdminOperaLaBandeja() {
        assertThrows(ForbiddenException.class, () -> service.listOpen(BUYER));
    }

    // ---------- manejo de errores de correo y PDF ----------

    /** Cierra una inspeccion y devuelve el evento que reports publico (el que consumen las colas). */
    InspectionCompleted closed(String n) {
        service.registerPaidOrder(order(n));
        service.complete(ADMIN, openId(n), form(InspectionStatus.PASSED));
        return published(RabbitConfig.KEY_COMPLETED, InspectionCompleted.class);
    }

    @Test
    void unCorreoNoEnviadoQuedaRegistradoApareceEnCorreosYSePuedeReenviar() {
        InspectionCompleted completed = closed("21");
        String id = completed.inspectionId();
        assertEquals(NotificationStatus.PENDING, service.get(ADMIN, id).notifications().confirmation().status());

        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.notSent("Sin SMTP"));
        reports.sendConfirmation(completed);
        assertTrue(service.listNotificationProblems(ADMIN).stream().anyMatch(i -> i.id().equals(id)),
                "una inspeccion con correo no enviado debe aparecer en la pestana de correos");

        // el admin reenvia: vuelve a PENDING y se republica inspection.completed
        var resent = service.resendNotifications(ADMIN, id);
        assertEquals(NotificationStatus.PENDING, resent.notifications().confirmation().status());
        assertEquals(null, resent.notifications().confirmation().error());
        verify(rabbit, times(2)).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.KEY_COMPLETED),
                any(Object.class), any(CorrelationData.class));

        // SMTP arreglado: el consumidor lo envia y deja de aparecer como problema
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.ok());
        reports.sendConfirmation(completed);
        assertEquals(NotificationStatus.SENT, service.get(ADMIN, id).notifications().confirmation().status());
        assertTrue(service.listNotificationProblems(ADMIN).stream().noneMatch(i -> i.id().equals(id)));
    }

    @Test
    void elReenvioNoRepiteLoQueYaSeEnvio() throws Exception {
        InspectionCompleted completed = closed("22");
        String id = completed.inspectionId();
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.ok());
        when(mail.sendPdf(anyList(), anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(MailResult.ok());
        reports.sendConfirmation(completed);
        reports.generate(completed);
        reports.mailReport(published(RabbitConfig.KEY_REPORT_GENERATED, ReportGenerated.class));
        var notifications = service.get(ADMIN, id).notifications();
        assertEquals(NotificationStatus.SENT, notifications.confirmation().status());
        assertEquals(NotificationStatus.SENT, notifications.report().status());

        service.resendNotifications(ADMIN, id); // nada pendiente: no republica
        verify(rabbit, times(1)).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.KEY_COMPLETED),
                any(Object.class), any(CorrelationData.class));
    }

    @Test
    void soloSePuedeReenviarUnaInspeccionCerradaYSoloElAdmin() {
        service.registerPaidOrder(order("23"));
        String openId = openId("23");
        assertThrows(ConflictException.class, () -> service.resendNotifications(ADMIN, openId));
        assertThrows(ForbiddenException.class, () -> service.resendNotifications(BUYER, openId));
        assertThrows(ForbiddenException.class, () -> service.listNotificationProblems(BUYER));
    }

    @Test
    void siElInformeNoSePuedeEnviarQuedaNoEnviadoYSinMarcaDeEnvio() {
        InspectionCompleted completed = closed("24");
        reports.generate(completed);
        ReportGenerated generated = published(RabbitConfig.KEY_REPORT_GENERATED, ReportGenerated.class);
        when(mail.sendPdf(anyList(), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(MailResult.notSent("El comprador no tiene correo registrado"));
        reports.mailReport(generated);

        var report = service.get(ADMIN, completed.inspectionId()).notifications().report();
        assertEquals(NotificationStatus.NOT_SENT, report.status());
        assertEquals("El comprador no tiene correo registrado", report.error());
        assertEquals(null, reports.latestTechnicalReport("item-24").getEmailedAt());
    }

    @Test
    void unInformeInexistenteEsUnErrorPermanenteQueNoSeReintenta() {
        var missing = new ReportGenerated(RabbitConfig.KEY_REPORT_GENERATED, Instant.now(), "no-existe", "i", "o",
                "item-x", "RTX", "comprador@correo.cl", InspectionStatus.PASSED);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> reports.mailReport(missing));
    }

    @Autowired
    RabbitListenerRetrySettingsCustomizer retryCustomizer;

    @Autowired
    MessageRecoverer failureRecorder;

    @Test
    void losErroresPermanentesNoSeReintentanPeroLosDemasSi() {
        var settings = new RetryPolicySettings();
        retryCustomizer.customize(settings);
        var wrapped = new org.springframework.amqp.rabbit.support.ListenerExecutionFailedException("x",
                new AmqpRejectAndDontRequeueException("permanente"), new Message(new byte[0]));
        assertFalse(settings.getExceptionPredicate().test(wrapped), "permanente (aun envuelto): no se reintenta");
        assertTrue(settings.getExceptionPredicate().test(new IllegalStateException("smtp caido")));
    }

    @Test
    void alAgotarLosIntentosSeRegistraElFalloYElMensajeVaALaColaDeErrores() {
        InspectionCompleted completed = closed("25");
        String id = completed.inspectionId();

        Message confirmation = failedMessage("mail.confirmation", id);
        assertThrows(Exception.class, () -> failureRecorder.recover(confirmation, new RuntimeException("smtp caido")));
        var afterConfirmation = service.get(ADMIN, id).notifications();
        assertEquals(NotificationStatus.FAILED, afterConfirmation.confirmation().status());
        assertTrue(afterConfirmation.confirmation().error().contains("Error inesperado"));

        Message report = failedMessage("report.generate", id);
        assertThrows(Exception.class, () -> failureRecorder.recover(report, new IllegalStateException("pdf roto")));
        assertEquals(NotificationStatus.FAILED, service.get(ADMIN, id).notifications().report().status());

        // un mensaje ilegible o de otra cola no impide mandarlo a la cola de errores
        assertThrows(Exception.class, () -> failureRecorder.recover(failedMessage("reports.new-order", "x"),
                new RuntimeException("boom")));
        assertTrue(service.listNotificationProblems(ADMIN).stream().anyMatch(i -> i.id().equals(id)));
    }

    Message failedMessage(String queue, String inspectionId) {
        var props = new MessageProperties();
        props.setConsumerQueue(queue);
        return new Message(("{\"inspectionId\":\"" + inspectionId + "\"}").getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void losFallosSeExplicanEnEspanolSegunSuCausa() {
        record Caso(Throwable causa, String esperado) { }
        var casos = List.of(
                new Caso(new org.springframework.mail.MailSendException("fallo",
                        new java.net.ConnectException("Connection refused")), "no responde"),
                new Caso(new java.net.SocketTimeoutException("timeout"), "no responde"),
                new Caso(new org.springframework.mail.MailAuthenticationException("535 Authentication failed"),
                        "rechazó las credenciales"),
                new Caso(new IllegalStateException("No se pudo generar el PDF"), "informe PDF"));
        for (Caso caso : casos) {
            org.mockito.Mockito.clearInvocations(rabbit); // closed() verifica una sola publicacion por inspeccion
            InspectionCompleted completed = closed("3" + casos.indexOf(caso));
            Message message = failedMessage("mail.confirmation", completed.inspectionId());
            assertThrows(Exception.class, () -> failureRecorder.recover(message, caso.causa()));
            String error = service.get(ADMIN, completed.inspectionId()).notifications().confirmation().error();
            assertTrue(error.contains(caso.esperado()), error);
            assertFalse(error.contains("Connection refused"), "el detalle tecnico no va al admin: " + error);
        }
    }

    // ---------- cancelar la compra ----------

    @Test
    void elAdminCancelaUnaPiezaAprobadaYSaleDeTodasLasListasDelTaller() {
        InspectionCompleted completed = closed("41");
        String id = completed.inspectionId();
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.notSent("Sin SMTP"));
        reports.sendConfirmation(completed);
        assertTrue(service.listNotificationProblems(ADMIN).stream().anyMatch(i -> i.id().equals(id)));
        assertTrue(service.listToDeliver(ADMIN).stream().anyMatch(i -> i.id().equals(id)));

        var cancelled = service.cancel(ADMIN, id);
        assertEquals(InspectionStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.completedAt());
        assertTrue(service.listNotificationProblems(ADMIN).stream().noneMatch(i -> i.id().equals(id)));
        assertTrue(service.listToDeliver(ADMIN).stream().noneMatch(i -> i.id().equals(id)));
        assertEquals(InspectionStatus.CANCELLED, service.get(ADMIN, id).status());

        // idempotente, y ya no se puede entregar, reenviar ni volver a revisar
        assertEquals(InspectionStatus.CANCELLED, service.cancel(ADMIN, id).status());
        assertThrows(ConflictException.class, () -> service.deliver(ADMIN, id));
        assertThrows(ConflictException.class, () -> service.resendNotifications(ADMIN, id));
        assertThrows(ConflictException.class, () -> service.complete(ADMIN, id, form(InspectionStatus.PASSED)));
    }

    @Test
    void sePuedeCancelarUnaPiezaQueAunNoSeRevisoYSaleDeLaBandeja() {
        service.registerPaidOrder(order("42"));
        String id = openId("42");
        assertEquals(InspectionStatus.CANCELLED, service.cancel(ADMIN, id).status());
        assertTrue(service.listOpen(ADMIN).stream().noneMatch(i -> i.id().equals(id)));
        assertThrows(ConflictException.class, () -> service.claim(ADMIN, id));
        assertThrows(ConflictException.class, () -> service.complete(ADMIN, id, form(InspectionStatus.PASSED)));
    }

    @Test
    void noSeCancelaUnaPiezaRechazadaNiEntregadaNiLoHaceQuienNoEsAdmin() {
        InspectionCompleted rejected = closedAs("43", InspectionStatus.FAILED);
        assertThrows(ConflictException.class, () -> service.cancel(ADMIN, rejected.inspectionId()));

        InspectionCompleted delivered = closedAs("44", InspectionStatus.PASSED);
        service.deliver(ADMIN, delivered.inspectionId());
        assertThrows(ConflictException.class, () -> service.cancel(ADMIN, delivered.inspectionId()));

        assertThrows(ForbiddenException.class, () -> service.cancel(BUYER, delivered.inspectionId()));
    }

    @Test
    void elComprador_yElVendedorVenLaCompraCancelada() {
        InspectionCompleted completed = closed("45");
        service.cancel(ADMIN, completed.inspectionId());
        var buyer = service.listAsBuyer(new AuthContext("buyer-1", null)).stream()
                .filter(i -> i.id().equals(completed.inspectionId())).findFirst().orElseThrow();
        assertEquals(InspectionStatus.CANCELLED, buyer.status());
        var sale = service.listAsSeller(new AuthContext("seller-1", null)).stream()
                .filter(i -> i.id().equals(completed.inspectionId())).findFirst().orElseThrow();
        assertEquals(InspectionStatus.CANCELLED, sale.status());
    }

    InspectionCompleted closedAs(String n, InspectionStatus result) {
        org.mockito.Mockito.clearInvocations(rabbit);
        service.registerPaidOrder(order(n));
        service.complete(ADMIN, openId(n), form(result));
        return published(RabbitConfig.KEY_COMPLETED, InspectionCompleted.class);
    }

    // ---------- descartar el aviso de correos ----------

    @Test
    void descartarElAvisoSacaLaInspeccionDeLaListaDeCorreosSinReenviarNada() {
        InspectionCompleted completed = closedAs("51", InspectionStatus.PASSED);
        String id = completed.inspectionId();
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.notSent("El comprador no tiene correo registrado"));
        reports.sendConfirmation(completed);
        assertTrue(service.listNotificationProblems(ADMIN).stream().anyMatch(i -> i.id().equals(id)));
        service.deliver(ADMIN, id); // entregada: ya no se puede cancelar, solo descartar el aviso
        assertThrows(ConflictException.class, () -> service.cancel(ADMIN, id));

        service.dismissNotifications(ADMIN, id);
        assertTrue(service.listNotificationProblems(ADMIN).stream().noneMatch(i -> i.id().equals(id)));
        // idempotente, no publica nada nuevo y deja los estados visibles
        service.dismissNotifications(ADMIN, id);
        verify(rabbit, times(1)).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.KEY_COMPLETED), any(Object.class), any(CorrelationData.class));
        assertEquals(NotificationStatus.NOT_SENT, service.get(ADMIN, id).notifications().confirmation().status());

        // si despues el admin reenvia, vuelve al seguimiento
        service.resendNotifications(ADMIN, id);
        when(mail.send(anyList(), anyString(), anyString())).thenReturn(MailResult.notSent("Sin correo"));
        reports.sendConfirmation(completed);
        assertTrue(service.listNotificationProblems(ADMIN).stream().anyMatch(i -> i.id().equals(id)));
    }

    @Test
    void soloSeDescartaElAvisoDeUnaInspeccionCerradaYSoloElAdmin() {
        service.registerPaidOrder(order("52"));
        String open = openId("52");
        assertThrows(ConflictException.class, () -> service.dismissNotifications(ADMIN, open));
        assertThrows(ForbiddenException.class, () -> service.dismissNotifications(BUYER, open));
    }
}
