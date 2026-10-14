package com.example.reports_service.pdf;

import com.example.reports_service.messaging.Events.ComponentCheck;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.InspectionDelayed;
import com.example.reports_service.model.InspectionStatus;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.List;

/** Arma los PDF. Devuelve bytes: donde se guardan lo decide ReportService (disco hoy, S3 manana). */
@Component
public class PdfReportGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
            .withZone(ZoneId.of("America/Santiago"));
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font TEXT = FontFactory.getFont(FontFactory.HELVETICA, 11);

    /** Informe tecnico: resultado general + estado de cada componente revisado. */
    public byte[] technicalReport(InspectionCompleted e) {
        return build(doc -> {
            doc.add(new Paragraph("Informe tecnico de inspeccion", TITLE));
            doc.add(new Paragraph("PC Market Builder", TEXT));
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("idproduct"));
            doc.add(line("Producto", e.description()));
            doc.add(line("Orden", e.orderId()));
            doc.add(line("Item", e.orderItemId()));
            doc.add(line("Fecha del informe", DATE.format(e.timestamp())));
            doc.add(line("Resultado", e.inspectionStatus() == InspectionStatus.PASSED
                    ? "APROBADA - el producto puede enviarse"
                    : "RECHAZADA - el producto no se enviara"));
            if (e.late()) {
                doc.add(line("Plazo", "La revision se completo despues del plazo estimado ("
                        + DATE.format(e.dueAt()) + ")"));
            }
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("Revision por componente", BOLD));
            doc.add(checksTable(e.checks()));
            doc.add(new Paragraph(" "));
            doc.add(line("Observaciones del taller",
                    e.workshopNotes() == null || e.workshopNotes().isBlank() ? "Sin observaciones" : e.workshopNotes()));
        });
    }

    /** Aviso al cliente: la revision supero el plazo, sigue en curso. */
    public byte[] delayNotice(InspectionDelayed e) {
        return build(doc -> {
            doc.add(new Paragraph("Aviso de demora en la inspeccion", TITLE));
            doc.add(new Paragraph("PC Market Builder", TEXT));
            doc.add(new Paragraph(" "));
            doc.add(line("Producto", e.description()));
            doc.add(line("Orden", e.orderId()));
            doc.add(line("Item", e.orderItemId()));
            doc.add(line("Plazo estimado", DATE.format(e.dueAt())));
            doc.add(line("Fecha del aviso", DATE.format(Instant.now())));
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("La revision tecnica de tu producto esta tomando mas tiempo del estimado. "
                    + "Sigue en curso: el producto no se enviara hasta que el agente tecnico termine la revision, "
                    + "y cuando lo haga recibiras el informe completo por correo. Tu pago permanece retenido de "
                    + "forma segura mientras tanto.", TEXT));
        });
    }

    private interface Body {
        void write(Document doc) throws DocumentException;
    }

    private byte[] build(Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            body.write(doc);
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo generar el PDF", e);
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    private static Paragraph line(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(label + ": ", BOLD));
        p.add(new Phrase(value == null ? "-" : value, TEXT));
        return p;
    }

    private static PdfPTable checksTable(List<ComponentCheck> checks) {
        PdfPTable table = new PdfPTable(new float[]{3, 1.5f, 5});
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);
        for (String h : new String[]{"Componente", "Estado", "Nota"}) {
            PdfPCell cell = new PdfPCell(new Phrase(h, BOLD));
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.addCell(cell);
        }
        if (checks == null || checks.isEmpty()) {
            PdfPCell cell = new PdfPCell(new Phrase("Sin detalle por componente", TEXT));
            cell.setColspan(3);
            table.addCell(cell);
            return table;
        }
        for (ComponentCheck c : checks) {
            table.addCell(new PdfPCell(new Phrase(c.component(), TEXT)));
            table.addCell(new PdfPCell(new Phrase(c.result() == null ? "-" : c.result().name(), TEXT)));
            table.addCell(new PdfPCell(new Phrase(c.note() == null ? "" : c.note(), TEXT)));
        }
        return table;
    }
}
