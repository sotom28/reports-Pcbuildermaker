package com.example.reports_service.controller;

import com.example.reports_service.auth.AuthContext;
import com.example.reports_service.dto.InspectionDtos.CompleteRequest;
import com.example.reports_service.dto.InspectionDtos.InspectionResponse;
import com.example.reports_service.service.InspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspections;

    /** Bandeja del admin: abiertas (las mas antiguas primero), ?scope=mine (las que cerro) o ?scope=buyer (mis compras). */
    @GetMapping
    public List<InspectionResponse> list(
            @RequestHeader(value = AuthContext.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = AuthContext.ROLE_HEADER, required = false) String role,
            @RequestParam(value = "scope", defaultValue = "open") String scope) {
        var auth = new AuthContext(userId, role);
        return switch (scope) {
            case "open" -> inspections.listOpen(auth);
            case "mine" -> inspections.listMine(auth);
            case "buyer" -> inspections.listAsBuyer(auth);
            default -> throw new IllegalArgumentException("El parametro 'scope' debe ser 'open', 'mine' o 'buyer'");
        };
    }

    @GetMapping("/{id}")
    public InspectionResponse get(
            @RequestHeader(value = AuthContext.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = AuthContext.ROLE_HEADER, required = false) String role,
            @PathVariable String id) {
        return inspections.get(new AuthContext(userId, role), id);
    }

    @PostMapping("/{id}/claim")
    public InspectionResponse claim(
            @RequestHeader(value = AuthContext.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = AuthContext.ROLE_HEADER, required = false) String role,
            @PathVariable String id) {
        return inspections.claim(new AuthContext(userId, role), id);
    }

    @PostMapping("/{id}/complete")
    public InspectionResponse complete(
            @RequestHeader(value = AuthContext.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = AuthContext.ROLE_HEADER, required = false) String role,
            @PathVariable String id,
            @Valid @RequestBody CompleteRequest request) {
        return inspections.complete(new AuthContext(userId, role), id, request);
    }
}
