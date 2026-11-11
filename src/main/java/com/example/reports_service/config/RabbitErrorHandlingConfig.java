package com.example.reports_service.config;

import com.example.reports_service.model.NotificationStatus;
import com.example.reports_service.service.InspectionService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailAuthenticationException;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * Que pasa cuando un listener falla:
 * <ul>
 *   <li>Los errores permanentes (AmqpRejectAndDontRequeueException, p. ej. un informe que no existe) NO se reintentan.</li>
 *   <li>Los demas se reintentan segun application.properties. Al agotarse, el mensaje va a su cola de errores (DLQ)
 *       y ANTES se deja constancia del fallo en la inspeccion, para que el admin lo vea y pueda reenviar los correos.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RabbitErrorHandlingConfig {

    @Bean
    RabbitListenerRetrySettingsCustomizer skipRetryForPermanentFailures() {
        return settings -> settings.setExceptionPredicate(error -> !isPermanent(error));
    }

    @Bean
    MessageRecoverer failureRecorder(InspectionService inspections) {
        JsonMapper mapper = JsonMapper.builder().build();
        RejectAndDontRequeueRecoverer toDeadLetterQueue = new RejectAndDontRequeueRecoverer();
        return (message, cause) -> {
            try {
                record(inspections, mapper, message, cause);
            } catch (RuntimeException e) {
                // Registrar el fallo nunca debe impedir que el mensaje llegue a la cola de errores.
                log.error("No se pudo registrar el fallo del mensaje de {}: {}", queueOf(message), e.getMessage());
            }
            toDeadLetterQueue.recover(message, cause);
        };
    }

    private static void record(InspectionService inspections, JsonMapper mapper, Message message, Throwable cause) {
        String queue = queueOf(message);
        String reason = describe(cause);
        log.error("Mensaje de la cola {} agoto los intentos y pasa a su cola de errores: {}", queue, reason);
        String inspectionId = inspectionIdOf(mapper, message);
        if (inspectionId == null) {
            return; // p. ej. reports.new-order: aun no existe la inspeccion
        }
        switch (queue) {
            case RabbitConfig.Q_MAIL_CONFIRMATION ->
                    inspections.recordConfirmation(inspectionId, NotificationStatus.FAILED, reason);
            case RabbitConfig.Q_REPORT_GENERATE, RabbitConfig.Q_MAIL_REPORT ->
                    inspections.recordReport(inspectionId, NotificationStatus.FAILED, reason);
            default -> { }
        }
    }

    static boolean isPermanent(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }
        return false;
    }

    private static String queueOf(Message message) {
        String queue = message.getMessageProperties().getConsumerQueue();
        return queue == null ? "?" : queue;
    }

    private static String inspectionIdOf(JsonMapper mapper, Message message) {
        try {
            Object id = mapper.readValue(message.getBody(), Map.class).get("inspectionId");
            return id == null ? null : id.toString();
        } catch (RuntimeException e) {
            return null; // cuerpo ilegible: nada que asociar
        }
    }

    /**
     * Motivo en espanol para el taller. El detalle tecnico (clase y mensaje de la excepcion) queda en el log; aqui solo
     * va lo que el admin necesita para decidir: si el sistema de correo no responde, si rechazo las credenciales, etc.
     */
    static String describe(Throwable error) {
        String reason = null;
        for (Throwable t = error; t != null && reason == null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AuthenticationFailedException || t instanceof MailAuthenticationException) {
                reason = "El sistema de correo rechazó las credenciales (usuario o contraseña del servidor de correo incorrectos)";
            } else if (t instanceof ConnectException || t instanceof SocketTimeoutException
                    || t instanceof UnknownHostException || t instanceof NoRouteToHostException
                    || t.getClass().getSimpleName().equals("MailConnectException")) {
                reason = "El sistema de correo no responde (no se pudo conectar con el servidor de correo)";
            } else if (t instanceof SendFailedException) {
                reason = "El servidor de correo rechazó el mensaje (la dirección del comprador puede ser inválida)";
            }
        }
        if (reason == null) {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String text = String.valueOf(error.getMessage()).toLowerCase() + " " + String.valueOf(root.getMessage()).toLowerCase();
            reason = text.contains("pdf")
                    ? "No se pudo generar o leer el informe PDF"
                    : "Error inesperado al procesar el correo (" + root.getClass().getSimpleName() + ")";
        }
        return reason + ". Se reintentó varias veces sin éxito.";
    }
}
