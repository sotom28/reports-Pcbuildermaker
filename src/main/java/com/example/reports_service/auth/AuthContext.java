package com.example.reports_service.auth;

import com.example.reports_service.exception.ApiExceptions.ForbiddenException;
import com.example.reports_service.exception.ApiExceptions.UnauthorizedException;

/**
 * Identidad de la request, tomada de los headers que inyecta el BFF tras validar
 * el JWT de Entra ID (mismo contrato que shipments y ms-user). Este servicio no
 * debe exponerse directo a internet: solo el BFF puede llegar a el.
 */
public record AuthContext(String userId, String role) {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";

    /** El agente tecnico del taller. */
    public static final String ROLE_WORKSHOP_ADMIN = "WORKSHOP_ADMIN";

    public AuthContext {
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("Falta el encabezado de identidad: " + USER_ID_HEADER);
        }
    }

    public boolean isAgent() {
        return ROLE_WORKSHOP_ADMIN.equals(role);
    }

    public void requireAgent() {
        if (!isAgent()) {
            throw new ForbiddenException("Solo el agente tecnico (WORKSHOP_ADMIN) puede hacer esto");
        }
    }
}
