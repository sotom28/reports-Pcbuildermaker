package com.example.reports_service.repository;

import com.example.reports_service.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepository extends JpaRepository<Report, String> {

    List<Report> findByOrderItemIdOrderByGeneratedAtDesc(String orderItemId);
}
