package com.example.reports_service.model;

public enum InspectionStatus {
    PENDING,    /// PENDIENTE: pagado, esperando agente
    IN_REVIEW,  /// EN REVISION: un agente la tomo
    PASSED,     /// APROBADA: se puede enviar
    FAILED;     /// RECHAZADA: no se envia

    public boolean isOpen() {
        return this == PENDING || this == IN_REVIEW;
    }
}
