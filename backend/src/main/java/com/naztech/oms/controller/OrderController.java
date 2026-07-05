package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.entity.OrderEvent;
import com.naztech.oms.repo.OrderEventRepo;
import com.naztech.oms.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;
    private final OrderEventRepo eventRepo;

    public OrderController(OrderService orders, OrderEventRepo eventRepo) {
        this.orders = orders;
        this.eventRepo = eventRepo;
    }

    @PostMapping
    public ResponseEntity<?> place(@RequestBody OrderRequest req,
                                   @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        try {
            OrderService.PlaceResult r = orders.place(req, actor);
            return ResponseEntity.ok(Map.of("order", r.order(), "risk", r.risk()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id,
                                    @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        try {
            return ResponseEntity.ok(orders.cancel(id, actor));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record ModifyRequest(java.math.BigDecimal price, Long quantity) {}

    @PutMapping("/{id}/modify")
    public ResponseEntity<?> modify(@PathVariable Long id, @RequestBody ModifyRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        try {
            OrderService.PlaceResult r = orders.modify(id, req.price(), req.quantity(), actor);
            return ResponseEntity.ok(Map.of("order", r.order(), "risk", r.risk()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public Object list(@RequestParam(required = false) Long brokerId,
                       @RequestParam(required = false) Long accountId) {
        if (accountId != null) return orders.blotterByAccount(accountId);
        if (brokerId != null) return orders.blotterByBroker(brokerId);
        return orders.recent();
    }

    @GetMapping("/{id}/events")
    public List<OrderEvent> events(@PathVariable Long id) {
        return eventRepo.findByOrderIdOrderByCreatedAtAsc(id);
    }
}
