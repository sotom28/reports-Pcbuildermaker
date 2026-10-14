package com.example.reports_service.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica a RabbitMQ solo despues de que la transaccion de BD se confirma, para no anunciar
 * algo que luego se revierte. Es "dispara y sigue": si el broker no responde se registra el
 * error y no se rompe la operacion del agente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePublisher {

    private final RabbitTemplate rabbit;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(OutboundMessage message) {
        try {
            rabbit.convertAndSend(message.exchange(), message.routingKey(), message.payload());
        } catch (RuntimeException e) {
            log.error("No se pudo publicar {} en {}: {}", message.routingKey(), message.exchange(), e.getMessage());
        }
    }
}
