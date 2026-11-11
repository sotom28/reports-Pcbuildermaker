package com.example.reports_service.model;

/** Estado de un envio de correo (confirmacion o informe PDF) de una inspeccion cerrada. */
public enum NotificationStatus {
    PENDING,   /// aun no se intenta o esta en curso
    SENT,      /// el correo salio
    NOT_SENT,  /// no se pudo enviar y reintentar no lo arregla (sin SMTP configurado o sin destinatario)
    FAILED     /// el SMTP o el PDF fallaron y se agotaron los intentos (el mensaje esta en la cola de errores)
}
