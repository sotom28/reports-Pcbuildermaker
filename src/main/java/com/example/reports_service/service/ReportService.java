package com.example.reports_service.service;

import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.exception.ApiExceptions.NotFoundException;
import com.example.reports_service.mail.MailService;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.ReportGenerated;
import com.example.reports_service.messaging.OutboundMessage;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.Report;
import com.example.reports_service.pdf.PdfReportGenerator;
import com.example.reports_service.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Las tres etapas posteriores a la revision, cada una en su propia cola:
 * confirmacion por correo, generacion del PDF, y envio del PDF por correo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final PdfReportGenerator pdf;
    private final ReportRepository reports;
    private final MailService mail;
    private final InspectionService inspections;
    private final ApplicationEventPublisher events;

    @Value("${app.reports.dir:reports-pdf}")
    private String dir;

    /** Etapa 1 (cola mail.confirmation): "todo salio bien / no aprobo". Idempotente por inspeccion. */
    public void sendConfirmation(InspectionCompleted e) {
        if (inspections.isConfirmationEmailed(e.inspectionId())) {
            log.info("Confirmacion de la inspeccion {} ya enviada, se ignora la reentrega", e.inspectionId());
            return;
        }
        boolean approved = e.inspectionStatus() == InspectionStatus.PASSED;
        String body = approved
                ? "Hola,\n\nLa revisión técnica de \"" + e.description() + "\" (orden " + e.orderId() + ") terminó "
                  + "correctamente. Tu pieza pasó todas las pruebas del taller y será enviada.\n\n"
                  + "Mantenimiento realizado: " + e.maintenanceDone() + "\n\n"
                  + "En unos instantes recibirás otro correo con el informe técnico en PDF.\n\nPC Market Builder"
                : "Hola,\n\nLa revisión técnica de \"" + e.description() + "\" (orden " + e.orderId() + ") terminó "
                  + "y la pieza no aprobó las pruebas del taller, por lo que no será enviada.\n\n"
                  + "Detalle: " + e.maintenanceDone() + "\n\n"
                  + "En unos instantes recibirás otro correo con el informe técnico en PDF.\n\nPC Market Builder";
        boolean sent = mail.send(List.of(nullToEmpty(e.buyerEmail())),
                approved ? "Tu pieza pasó la revisión técnica y será enviada" : "Tu pieza no aprobó la revisión técnica",
                body);
        if (sent) {
            inspections.markConfirmationEmailed(e.inspectionId());
        }
    }

    /**
     * Etapa 2 (cola report.generate): crea el PDF, lo guarda y avisa que existe (report.generated).
     * Idempotente por item: si el PDF ya existia se vuelve a anunciar solo si nadie lo envio aun, asi una
     * publicacion perdida se recupera con la reentrega.
     */
    public void generate(InspectionCompleted e) {
        Report existing = reports.findByOrderItemIdOrderByGeneratedAtDesc(e.orderItemId()).stream()
                .filter(r -> r.getType() == Report.Type.TECHNICAL_REPORT)
                .findFirst().orElse(null);
        if (existing != null) {
            log.info("Informe del item {} ya existe", e.orderItemId());
            if (existing.getEmailedAt() == null) {
                announce(existing, e);
            }
            return;
        }
        Report report = store(e.orderItemId(), Report.Type.TECHNICAL_REPORT, pdf.technicalReport(e));
        log.info("Informe {} generado para el item {}", report.getReportId(), e.orderItemId());
        announce(report, e);
    }

    /** Etapa 3 (cola mail.report): envia el PDF por correo. Idempotente por informe. */
    public void mailReport(ReportGenerated e) {
        Report report = reports.findById(e.reportId())
                .orElseThrow(() -> new NotFoundException("Informe no encontrado: " + e.reportId()));
        if (report.getEmailedAt() != null) {
            log.info("Informe {} ya enviado, se ignora la reentrega", report.getReportId());
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(report.getPdfUrl()));
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer el PDF " + report.getPdfUrl(), ex);
        }
        boolean approved = e.inspectionStatus() == InspectionStatus.PASSED;
        boolean sent = mail.sendPdf(List.of(nullToEmpty(e.buyerEmail())),
                "Informe técnico: " + (approved ? "producto aprobado" : "producto rechazado"),
                "Hola,\n\nAdjuntamos el informe técnico de la revisión de \"" + e.description() + "\" (orden "
                        + e.orderId() + ").\nResultado: " + (approved ? "APROBADO, se enviará a tu dirección."
                        : "RECHAZADO, no se enviará.") + "\n\nPC Market Builder",
                "informe-tecnico-" + e.orderItemId() + ".pdf", bytes);
        if (sent) {
            report.setEmailedAt(Instant.now());
            reports.save(report);
        }
    }

    /** Cuantos informes tiene el item (debe ser 1: la generacion es idempotente). */
    public long reportCount(String orderItemId) {
        return reports.findByOrderItemIdOrderByGeneratedAtDesc(orderItemId).size();
    }

    /** Ultimo informe tecnico del item. */
    public Report latestTechnicalReport(String orderItemId) {
        return reports.findByOrderItemIdOrderByGeneratedAtDesc(orderItemId).stream()
                .filter(r -> r.getType() == Report.Type.TECHNICAL_REPORT)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Aun no hay informe para el item " + orderItemId));
    }

    public Resource file(Report report) {
        Path path = Path.of(report.getPdfUrl());
        if (!Files.exists(path)) {
            throw new NotFoundException("El archivo del informe ya no esta disponible");
        }
        return new FileSystemResource(path);
    }

    private void announce(Report report, InspectionCompleted e) {
        events.publishEvent(new OutboundMessage(RabbitConfig.EXCHANGE, RabbitConfig.KEY_REPORT_GENERATED,
                new ReportGenerated(RabbitConfig.KEY_REPORT_GENERATED, Instant.now(), report.getReportId(),
                        e.inspectionId(), e.orderId(), e.orderItemId(), e.description(), e.buyerEmail(),
                        e.inspectionStatus())));
    }

    private Report store(String orderItemId, Report.Type type, byte[] bytes) {
        try {
            Path folder = Path.of(dir);
            Files.createDirectories(folder);
            Path file = folder.resolve(type.name().toLowerCase() + "-" + orderItemId + ".pdf");
            Files.write(file, bytes);
            Report r = new Report();
            r.setOrderItemId(orderItemId);
            r.setType(type);
            r.setPdfUrl(file.toString());
            return reports.save(r);
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo guardar el PDF", ex);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
