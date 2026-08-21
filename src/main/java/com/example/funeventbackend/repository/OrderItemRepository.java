package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    // 取消訂單時要知道該回補哪些票種、各幾張
    List<OrderItem> findByOrderId(Long orderId);

    // 刪除票種前的檢查：有任何訂單參照就不能刪
    boolean existsByTicketTypeId(Long ticketTypeId);
}
