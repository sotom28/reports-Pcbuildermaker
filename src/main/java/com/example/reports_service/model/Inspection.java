package com.example.reports_service.model;

import com.example.reports_service.messaging.Events.ComponentResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "inspections", indexes = {
        @Index(name = "idx_inspection_status", columnList = "status"),
        @Index(name = "idx_inspection_agent", columnList = "agentId")
})
@Getter
@Setter
@NoArgsConstructor
public class Inspection {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    /** Un item pagado = una inspeccion: hace idempotente la recepcion del evento. */
    @Column(nullable = false, unique = true, length = 36)
    private String orderItemId;

    @Column(nullable = false, length = 36)
    private String orderId;

    @Column(length = 36)
    private String publicationId;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, length = 64)
    private String buyerId;

    @Column(nullable = false, length = 64)
    private String sellerId;

    /** Correo que el comprador indico al pagar. Unico destinatario de confirmacion e informe. */
    @Column(length = 150)
    private String buyerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InspectionStatus status = InspectionStatus.PENDING;

    /** Admin del taller que cerro la revision (azure_oid). */
    @Column(length = 64)
    private String agentId;

    /** Formulario: que mantenimiento se le hizo a la pieza. */
    @Column(length = 1000)
    private String maintenanceDone;

    /** Formulario: fecha en que se hizo el mantenimiento. */
    private LocalDate maintenanceDate;

    @Column(length = 1000)
    private String workshopNotes;

    /** Revision componente por componente (CPU, GPU, fuente...). Opcional. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "inspection_checks", joinColumns = @JoinColumn(name = "inspection_id"))
    private List<CheckEntry> checks = new ArrayList<>();

    /** Cuando se envio el correo de confirmacion (null = aun no). Evita duplicarlo si RabbitMQ reentrega. */
    private Instant confirmationEmailedAt;

    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class CheckEntry {
        @Column(length = 100)
        private String component;

        @Enumerated(EnumType.STRING)
        @Column(length = 10)
        private ComponentResult result;

        @Column(length = 255)
        private String note;

        public CheckEntry(String component, ComponentResult result, String note) {
            this.component = component;
            this.result = result;
            this.note = note;
        }
    }
}
