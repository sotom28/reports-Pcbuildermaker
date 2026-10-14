package com.example.reports_service.messaging;

/** Mensaje a publicar tras el commit de la transaccion (ver MessagePublisher). */
public record OutboundMessage(String exchange, String routingKey, Object payload) {
}
