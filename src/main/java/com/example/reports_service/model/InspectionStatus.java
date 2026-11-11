package com.example.reports_service.model;

public enum InspectionStatus {
    PENDING,    /// PENDIENTE: pagado, esperando agente
    IN_REVIEW,  /// EN REVISION: un agente la tomo
    PASSED,     /// APROBADA: se puede enviar
    FAILED,     /// RECHAZADA: no se envia
    CANCELLED;  /// COMPRA CANCELADA por el taller (solo visible: se deriva de Inspection.cancelledAt, nunca se guarda en la columna status)

    public boolean isOpen() {
        return this == PENDING || this == IN_REVIEW;
    }
}
