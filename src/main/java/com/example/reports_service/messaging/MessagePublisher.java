package com.example.reports_service.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica a RabbitMQ solo despues de que la transaccion de BD se confirma, para no anunciar
 * algo que luego se revierte. Es "dispara y sigue": si el broker no responde se registra el
 * error y no se rompe la operacion del admin. Con publisher confirms activos, un rechazo del
 * broker (nack) o un mensaje sin cola destino (returned) tambien queda en el log.
 */
@Slf4j
@Component
public class MessagePublisher {

    private final RabbitTemplate rabbit;

    public MessagePublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
        rabbit.setMandatory(true);
        rabbit.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ rechazo el mensaje {}: {}", correlation == null ? "?" : correlation.getId(), cause);
            }
        });
        rabbit.setReturnsCallback(returned -> log.error("Mensaje sin cola destino: exchange={} key={} motivo={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(OutboundMessage message) {
        try {
            rabbit.convertAndSend(message.exchange(), message.routingKey(), message.payload(),
                    new CorrelationData(message.routingKey()));
        } catch (RuntimeException e) {
            log.error("No se pudo publicar {} en {}: {}", message.routingKey(), message.exchange(), e.getMessage());
        }
    }
}
