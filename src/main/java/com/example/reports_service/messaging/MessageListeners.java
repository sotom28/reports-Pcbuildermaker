package com.example.reports_service.messaging;

import com.example.reports_service.config.RabbitConfig;
import com.example.reports_service.messaging.Events.InspectionCompleted;
import com.example.reports_service.messaging.Events.OrderPaid;
import com.example.reports_service.messaging.Events.ReportGenerated;
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

    /** Pedido pagado: cada pieza queda en la bandeja del admin, antes de enviarse. */
    @RabbitListener(queues = RabbitConfig.Q_NEW_ORDER)
    public void onOrderPaid(OrderPaid event) {
        inspections.registerPaidOrder(event);
    }

    /** Formulario enviado -> correo de confirmacion al comprador. */
    @RabbitListener(queues = RabbitConfig.Q_MAIL_CONFIRMATION)
    public void onConfirmationMail(InspectionCompleted event) {
        reports.sendConfirmation(event);
    }

    /** Formulario enviado -> genera el PDF y avisa con report.generated. */
    @RabbitListener(queues = RabbitConfig.Q_REPORT_GENERATE)
    public void onGenerateReport(InspectionCompleted event) {
        reports.generate(event);
    }

    /** PDF generado -> correo con el PDF adjunto. */
    @RabbitListener(queues = RabbitConfig.Q_MAIL_REPORT)
    public void onReportMail(ReportGenerated event) {
        reports.mailReport(event);
    }
}
