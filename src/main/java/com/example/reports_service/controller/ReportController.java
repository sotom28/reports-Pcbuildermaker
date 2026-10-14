package com.example.reports_service.controller;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.model.Report;
import com.example.reports_service.service.InspectionService;
import com.example.reports_service.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;
    private final InspectionService inspections;

    /** Descarga el informe tecnico del item. Solo comprador, vendedor o agente. */
    @GetMapping("/{orderItemId}")
    public ResponseEntity<Resource> download(
            @RequestHeader(value = AuthContext.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = AuthContext.ROLE_HEADER, required = false) String role,
            @PathVariable String orderItemId) {
        inspections.requireParticipantByItem(new AuthContext(userId, role), orderItemId);
        Report report = reports.latestTechnicalReport(orderItemId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("informe-tecnico-" + orderItemId + ".pdf").build().toString())
                .body(reports.file(report));
    }
}
