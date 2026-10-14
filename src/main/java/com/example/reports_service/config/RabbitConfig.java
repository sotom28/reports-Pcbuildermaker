package com.example.reports_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Topologia de RabbitMQ de reports-service.
 *
 * <pre>
 * payments --order.paid--> [pcmarketbuilder.events: topic] --> reports.new-order --> crea una inspeccion por item
 *
 * (al crear la inspeccion) --> [pcmarketbuilder.delay: direct] --> reports.wait-deadline
 *     cola SIN consumidor con TTL = plazo (72 h); al vencer RabbitMQ la reenvia por dead-letter:
 *     --inspection.deadline--> [pcmarketbuilder.events] --> reports.deadline --> aviso de retraso
 *
 * agente cierra la revision --inspection.completed--> reports.technical-review --> PDF del informe + correo
 * plazo vencido sin revisar --inspection.delayed----> reports.delay-notice -----> PDF de aviso + correo
 * </pre>
 *
 * Otros servicios (payments, shipments) pueden bindear su propia cola a inspection.completed.
 */
@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "pcmarketbuilder.events";
    public static final String DELAY_EXCHANGE = "pcmarketbuilder.delay";

    public static final String KEY_ORDER_PAID = "order.paid";
    public static final String KEY_DEADLINE = "inspection.deadline";
    public static final String KEY_COMPLETED = "inspection.completed";
    public static final String KEY_DELAYED = "inspection.delayed";
    public static final String KEY_WAIT = "inspection.wait";

    public static final String Q_NEW_ORDER = "reports.new-order";
    public static final String Q_WAIT = "reports.wait-deadline";
    public static final String Q_DEADLINE = "reports.deadline";
    public static final String Q_TECHNICAL_REVIEW = "reports.technical-review";
    public static final String Q_DELAY_NOTICE = "reports.delay-notice";

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE, true, false);
    }

    @Bean
    Queue newOrderQueue() {
        return QueueBuilder.durable(Q_NEW_ORDER).build();
    }

    @Bean
    Queue deadlineQueue() {
        return QueueBuilder.durable(Q_DEADLINE).build();
    }

    @Bean
    Queue technicalReviewQueue() {
        return QueueBuilder.durable(Q_TECHNICAL_REVIEW).build();
    }

    @Bean
    Queue delayNoticeQueue() {
        return QueueBuilder.durable(Q_DELAY_NOTICE).build();
    }

    /**
     * TTL a nivel de cola (no por mensaje): el TTL por mensaje solo expira cuando el mensaje
     * llega al frente de la cola, y como todos esperan lo mismo la cola es FIFO y alcanza.
     * Cambiar el plazo en una cola ya creada exige borrarla en RabbitMQ (reports.wait-deadline).
     */
    @Bean
    Queue waitQueue(@Value("${app.inspection.deadline}") Duration deadline) {
        return QueueBuilder.durable(Q_WAIT)
                .ttl((int) deadline.toMillis())
                .deadLetterExchange(EVENTS_EXCHANGE)
                .deadLetterRoutingKey(KEY_DEADLINE)
                .build();
    }

    @Bean
    Binding newOrderBinding(Queue newOrderQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(newOrderQueue).to(eventsExchange).with(KEY_ORDER_PAID);
    }

    @Bean
    Binding deadlineBinding(Queue deadlineQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(deadlineQueue).to(eventsExchange).with(KEY_DEADLINE);
    }

    @Bean
    Binding technicalReviewBinding(Queue technicalReviewQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(technicalReviewQueue).to(eventsExchange).with(KEY_COMPLETED);
    }

    @Bean
    Binding delayNoticeBinding(Queue delayNoticeQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(delayNoticeQueue).to(eventsExchange).with(KEY_DELAYED);
    }

    @Bean
    Binding waitBinding(Queue waitQueue, DirectExchange delayExchange) {
        return BindingBuilder.bind(waitQueue).to(delayExchange).with(KEY_WAIT);
    }

    /** JSON entre servicios. Se ignora el header __TypeId__ del productor (clases distintas por servicio). */
    @Bean
    MessageConverter messageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }
}
