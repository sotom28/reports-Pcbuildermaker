package com.example.reports_service.repository;

import com.example.reports_service.model.Inspection;
import com.example.reports_service.model.InspectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InspectionRepository extends JpaRepository<Inspection, String> {

    boolean existsByOrderItemId(String orderItemId);

    Optional<Inspection> findByOrderItemId(String orderItemId);

    /** Bandeja del admin: las mas antiguas primero. */
    List<Inspection> findByStatusInOrderByCreatedAtAsc(Collection<InspectionStatus> statuses);

    List<Inspection> findByAgentIdOrderByCreatedAtDesc(String agentId);

    /** Compras del usuario ("Mis compras"). */
    List<Inspection> findByBuyerIdOrderByCreatedAtDesc(String buyerId);

    /** Ventas del usuario ("Mis ventas"). */
    List<Inspection> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    /** Aprobadas que el taller aun no entrega: la pestana "Por entregar" del admin, las mas antiguas primero. */
    List<Inspection> findByStatusAndDeliveredAtIsNullOrderByCompletedAtAsc(InspectionStatus status);
}
