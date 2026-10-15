package com.example.reports_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Topologia de RabbitMQ: un unico exchange direct con tres routing keys.
 *
 * <pre>
 * payments --order.paid--> [pcmarketbuilder.direct] --> reports.new-order --> crea una inspeccion por item
 *
 * admin envia el formulario --inspection.completed--> [pcmarketbuilder.direct]
 *     |-> mail.confirmation : correo "todo salio bien, tu pieza sera enviada"
 *     '-> report.generate   : genera y guarda el PDF --report.generated--> [pcmarketbuilder.direct]
 *                                 '-> mail.report : correo con el PDF adjunto
 * </pre>
 *
 * Dos colas con la misma routing key (inspection.completed) = cada una recibe su propia copia,
 * asi la confirmacion y el PDF corren en paralelo y se reintentan por separado.
 *
 * Toda cola de trabajo tiene una cola muerta (&lt;cola&gt;.dlq): si un listener agota sus reintentos el
 * mensaje cae ahi en vez de perderse.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "pcmarketbuilder.direct";
    public static final String DLX = "pcmarketbuilder.dlx";

    public static final String KEY_ORDER_PAID = "order.paid";
    public static final String KEY_COMPLETED = "inspection.completed";
    public static final String KEY_REPORT_GENERATED = "report.generated";

    public static final String Q_NEW_ORDER = "reports.new-order";
    public static final String Q_MAIL_CONFIRMATION = "mail.confirmation";
    public static final String Q_REPORT_GENERATE = "report.generate";
    public static final String Q_MAIL_REPORT = "mail.report";

    private static final String DLQ_SUFFIX = ".dlq";

    @Bean
    DirectExchange directExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    /** Colas de trabajo + sus bindings + sus colas muertas, todo declarado junto. */
    @Bean
    Declarables topology(DirectExchange directExchange, DirectExchange deadLetterExchange) {
        List<Declarable> all = new ArrayList<>();
        bind(all, directExchange, deadLetterExchange, Q_NEW_ORDER, KEY_ORDER_PAID);
        bind(all, directExchange, deadLetterExchange, Q_MAIL_CONFIRMATION, KEY_COMPLETED);
        bind(all, directExchange, deadLetterExchange, Q_REPORT_GENERATE, KEY_COMPLETED);
        bind(all, directExchange, deadLetterExchange, Q_MAIL_REPORT, KEY_REPORT_GENERATED);
        return new Declarables(all);
    }

    private static void bind(List<Declarable> all, DirectExchange exchange, DirectExchange dlx,
                             String queueName, String routingKey) {
        Queue queue = QueueBuilder.durable(queueName)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(queueName + DLQ_SUFFIX)
                .build();
        Queue dead = QueueBuilder.durable(queueName + DLQ_SUFFIX).build();
        all.add(queue);
        all.add(dead);
        all.add(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        all.add(BindingBuilder.bind(dead).to(dlx).with(queueName + DLQ_SUFFIX));
    }

    /** JSON entre servicios. Se ignora el header __TypeId__ del productor (clases distintas por servicio). */
    @Bean
    MessageConverter messageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }
}
