package com.example.funeventbackend.service;

import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.Ticket;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {
    private final TicketRepository ticketRepository;
    private final OrderItemRepository orderItemRepository;

    /**
     * 把一張已付款訂單的每個明細展開成 N 張票。
     *
     * <p>⭐ 呼叫時機是「{@code markPaid} 回傳 1 之後」——
     * 那個條件式 UPDATE 已經保證了「只有從 PENDING 轉成 PAID 的那一次」會進來，
     * 所以重複的付款回呼不會重複發票。冪等做在狀態轉移上，下游全部受惠。
     *
     * <p>⚠️ 這裡跟 {@code PaymentService.handleCallback} 是<b>同一個交易</b>
     *（預設的 REQUIRED）。發票失敗會連同「標記已付款」一起回滾 ——
     * 那是想要的行為：與其留下一張付了錢卻沒有票的訂單，
     * 不如讓金流商重送回呼。訂單會退回 PENDING，重送時 markPaid 再回 1，自動補上。
     */
    @Transactional
    public void issueForOrder(Long orderId) {
        // ⚠️ 防呆而不是防併發 —— 真正擋住重複發票的是 markPaid 的狀態轉移。
        // 這裡是為了「有人之後從別的路徑呼叫」時不會默默發出第二批
        if (ticketRepository.existsByOrderItemOrderId(orderId)) {
            log.warn("訂單已經發過票，略過 orderId={}", orderId);
            return;
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        List<Ticket> tickets = new ArrayList<>();
        for (OrderItem item : items) {
            // quantity 張 → quantity 筆。⚠️ 一張票一列，
            // 這樣同一筆明細買的三張票才能分開入場
            for (int i = 0; i < item.getQuantity(); i++) {
                tickets.add(Ticket.builder().orderItem(item).build());
            }
        }
        ticketRepository.saveAll(tickets);
        log.info("已發出票券 orderId={} 張數={}", orderId, tickets.size());
    }
}
