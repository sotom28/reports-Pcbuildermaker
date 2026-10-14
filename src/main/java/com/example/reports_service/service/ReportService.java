package com.example.reports_service.service;

import com.example.reports_service.exception.ApiExceptions.NotFoundException;
import com.example.reports_service.mail.MailService;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.InspectionDelayed;
import com.example.reports_service.model.InspectionStatus;
import com.example.reports_service.model.Report;
import com.example.reports_service.pdf.PdfReportGenerator;
import com.example.reports_service.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Genera el PDF, lo guarda (disco local en el MVP), registra la metadata y lo envia por correo. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final PdfReportGenerator pdf;
    private final ReportRepository reports;
    private final MailService mail;

    @Value("${app.reports.dir:reports-pdf}")
    private String dir;

    /** Idempotente por item+tipo: si RabbitMQ reentrega el mensaje no se duplica el informe ni el correo. */
    public void onCompleted(InspectionCompleted e) {
        if (alreadyGenerated(e.orderItemId(), Report.Type.TECHNICAL_REPORT)) {
            return;
        }
        byte[] bytes = pdf.technicalReport(e);
        Report report = store(e.orderItemId(), Report.Type.TECHNICAL_REPORT, bytes);
        boolean approved = e.inspectionStatus() == InspectionStatus.PASSED;
        boolean sent = mail.sendPdf(List.of(e.buyerEmail(), e.sellerEmail()),
                "Informe tecnico: " + (approved ? "producto aprobado" : "producto rechazado"),
                "Adjuntamos el informe de la revision tecnica de \"" + e.description() + "\" (orden " + e.orderId()
                        + ").\nResultado: " + (approved ? "APROBADO, se enviara al comprador." : "RECHAZADO, no se enviara.")
                        + "\n\nPC Market Builder",
                "informe-tecnico-" + e.orderItemId() + ".pdf", bytes);
        markEmailed(report, sent);
    }

    public void onDelayed(InspectionDelayed e) {
        if (alreadyGenerated(e.orderItemId(), Report.Type.DELAY_NOTICE)) {
            return;
        }
        byte[] bytes = pdf.delayNotice(e);
        Report report = store(e.orderItemId(), Report.Type.DELAY_NOTICE, bytes);
        boolean sent = mail.sendPdf(List.of(e.buyerEmail(), e.sellerEmail()),
                "Aviso: la revision tecnica de tu producto esta demorada",
                "La revision de \"" + e.description() + "\" (orden " + e.orderId() + ") supero el plazo estimado. "
                        + "Sigue en curso y te enviaremos el informe en cuanto termine. Detalle en el PDF adjunto."
                        + "\n\nPC Market Builder",
                "aviso-demora-" + e.orderItemId() + ".pdf", bytes);
        markEmailed(report, sent);
    }

    /** Ultimo informe tecnico del item (el de aviso de demora no cuenta como informe). */
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

    private boolean alreadyGenerated(String orderItemId, Report.Type type) {
        boolean exists = reports.findByOrderItemIdOrderByGeneratedAtDesc(orderItemId).stream()
                .anyMatch(r -> r.getType() == type);
        if (exists) {
            log.info("{} del item {} ya existe, se ignora la reentrega", type, orderItemId);
        }
        return exists;
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
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el PDF", e);
        }
    }

    private void markEmailed(Report report, boolean sent) {
        if (sent) {
            report.setEmailedAt(Instant.now());
            reports.save(report);
        }
    }
}
