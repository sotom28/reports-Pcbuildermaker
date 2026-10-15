package com.example.reports_service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Envia correos, con o sin PDF adjunto. El SMTP es opcional: sin spring.mail.host no hay JavaMailSender
 * y se registra en el log en vez de fallar (util en desarrollo y en tests).
 */
@Slf4j
@Service
public class MailService {

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final boolean smtpConfigured;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Value("${app.mail.from:no-reply@pcmarketbuilder.local}") String from,
                       @Value("${spring.mail.host:}") String smtpHost) {
        this.sender = sender;
        this.from = from;
        // Spring crea el JavaMailSender aunque el host este vacio (apuntaria a localhost:25): se valida aqui.
        this.smtpConfigured = smtpHost != null && !smtpHost.isBlank();
    }

    /** Correo de texto plano, sin adjunto. */
    public boolean send(List<String> to, String subject, String body) {
        return send(to, subject, body, null, null);
    }

    /** Correo con el PDF adjunto. */
    public boolean sendPdf(List<String> to, String subject, String body, String filename, byte[] pdf) {
        return send(to, subject, body, filename, pdf);
    }

    /** @return true si se envio; false si no hay SMTP o no hay destinatarios. Lanza si el SMTP falla (reintenta el listener). */
    private boolean send(List<String> to, String subject, String body, String filename, byte[] pdf) {
        List<String> recipients = to.stream().filter(a -> a != null && !a.isBlank()).distinct().toList();
        JavaMailSender mailSender = smtpConfigured ? sender.getIfAvailable() : null;
        if (recipients.isEmpty() || mailSender == null) {
            log.warn("Correo '{}' no enviado (destinatarios={}, smtp configurado={})", subject, recipients,
                    mailSender != null);
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, pdf != null, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipients.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(body);
            if (pdf != null) {
                helper.addAttachment(filename, new ByteArrayResource(pdf), "application/pdf");
            }
            mailSender.send(message);
            log.info("Correo '{}' enviado a {}", subject, recipients);
            return true;
        } catch (MessagingException e) {
            throw new IllegalStateException("No se pudo armar el correo", e);
        }
    }
}
