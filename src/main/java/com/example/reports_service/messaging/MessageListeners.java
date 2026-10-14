package com.example.reports_service.messaging;

import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.messaging.Events.DeadlineReached;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.InspectionDelayed;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.service.InspectionService;
import com.example.reports_service.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessageListeners {

    private final InspectionService inspections;
    private final ReportService reports;

    /** Pedido pagado: cada pieza queda en la bandeja del agente tecnico, antes de enviarse. */
    @RabbitListener(queues = RabbitConfig.Q_NEW_ORDER)
    public void onOrderPaid(OrderPaid event) {
        inspections.registerPaidOrder(event);
    }

    /** Llega aqui cuando el mensaje de la cola de espera vence (dead-letter). */
    @RabbitListener(queues = RabbitConfig.Q_DEADLINE)
    public void onDeadline(DeadlineReached message) {
        inspections.onDeadline(message.inspectionId());
    }

    /** Revision terminada: PDF del informe por componente y correo. */
    @RabbitListener(queues = RabbitConfig.Q_TECHNICAL_REVIEW)
    public void onInspectionCompleted(InspectionCompleted event) {
        reports.onCompleted(event);
    }

    /** Plazo vencido sin revisar: PDF de aviso y correo al cliente. */
    @RabbitListener(queues = RabbitConfig.Q_DELAY_NOTICE)
    public void onInspectionDelayed(InspectionDelayed event) {
        reports.onDelayed(event);
    }
}
