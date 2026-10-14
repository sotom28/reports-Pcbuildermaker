package com.example.reports_service.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Metadata del PDF generado (el binario vive en disco local en el MVP; S3 key en produccion). */
@Entity
@Table(name = "reports", indexes = @Index(name = "idx_report_item", columnList = "orderItemId"))
@Getter
@Setter
@NoArgsConstructor
public class Report {

    public enum Type { TECHNICAL_REPORT, DELAY_NOTICE }

    @Id
    @Column(length = 36)
    private String reportId = UUID.randomUUID().toString();

    @Column(nullable = false, length = 36)
    private String orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 500)
    private String pdfUrl;

    /** Cuando se envio por correo (null = no enviado o sin SMTP configurado). */
    private Instant emailedAt;

    @Column(nullable = false, updatable = false)
    private Instant generatedAt = Instant.now();
}
