package com.naztech.oms.service;

import com.naztech.oms.entity.AuditLog;
import com.naztech.oms.entity.OrderEvent;
import com.naztech.oms.repo.AuditLogRepo;
import com.naztech.oms.repo.OrderEventRepo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Configurable audit trail (RFP "Audit Trail" / "Monitoring & Manageability"). */
@Service
public class AuditService {

    private final AuditLogRepo auditRepo;
    private final OrderEventRepo eventRepo;

    public AuditService(AuditLogRepo auditRepo, OrderEventRepo eventRepo) {
        this.auditRepo = auditRepo;
        this.eventRepo = eventRepo;
    }

    public void audit(String actor, String action, String entityType, String entityId, String detail) {
        AuditLog a = new AuditLog();
        a.setActor(actor);
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setDetail(detail);
        a.setCreatedAt(LocalDateTime.now());
        auditRepo.save(a);
    }

    public void orderEvent(Long orderId, String type, String detail) {
        OrderEvent e = new OrderEvent();
        e.setOrderId(orderId);
        e.setEventType(type);
        e.setDetail(detail);
        e.setCreatedAt(LocalDateTime.now());
        eventRepo.save(e);
    }
}
