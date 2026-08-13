package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    // 明細靠 Order.orderItems 上的 @BatchSize 批次載入，
    // 這裡不能用 @EntityGraph —— 抓集合會讓 Hibernate 改成在記憶體裡分頁
    Page<Order> findByUser(User user, Pageable pageable);

    // 把「是不是這個人的」寫進查詢條件，而不是查完再用 if 判斷：
    // 「不存在」和「不是你的」自然合流成同一個結果，不會有兩條分支洩漏差異
    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findByIdAndUser(Long id, User user);
}
