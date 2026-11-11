package com.example.reports_service.mail;

/**
 * Resultado de intentar enviar un correo. Un fallo real del SMTP no llega aqui: se lanza como excepcion para que el
 * listener lo reintente. NOT_SENT es lo que no se arregla reintentando (sin SMTP configurado, sin destinatario).
 */
public record MailResult(boolean sent, String reason) {

    public static MailResult ok() {
        return new MailResult(true, null);
    }

    public static MailResult notSent(String reason) {
        return new MailResult(false, reason);
    }
}
