package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.ticket.CheckInProgressResponse;
import com.example.funeventbackend.dto.ticket.CheckInResponse;
import com.example.funeventbackend.dto.ticket.TicketResponse;
import com.example.funeventbackend.dto.ticket.TicketStatusCount;
import com.example.funeventbackend.dto.ticket.TicketTypeCheckInProgress;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.Ticket;
import com.example.funeventbackend.model.TicketStatus;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.TicketRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.security.TicketTokenSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {
    private final TicketRepository ticketRepository;
    // 核銷進度要列出「一張都沒賣的票種」，那些不會出現在 GROUP BY 的結果裡
    private final TicketTypeRepository ticketTypeRepository;
    private final OrderItemRepository orderItemRepository;
    private final TicketTokenSigner ticketTokenSigner;
    // 核銷時確認「這個活動是不是你的」
    private final EventService eventService;
    // 「我的票券」時確認「這張訂單是不是你的」
    private final OrderService orderService;

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

    /**
     * 「我的票券」：某張訂單的所有票，含可入場的 QR 內容。
     *
     * <p>⚠️ 擁有權交給 {@code orderService.findByIdAndUser} —— 別人的訂單
     * 一律 404（訂單是私有資源，403 等於證實了這個 id 存在）。
     * 驗過才撈票。
     *
     * <p>⭐ qrContent 是<b>現算</b>的，不是查出來的 —— 票券沒有 token 欄位，
     * 簽章可以從 id 隨時重現。這正是選簽章而不是隨機 token 的理由。
     */
    @Transactional(readOnly = true)
    public List<TicketResponse> findByOrder(User user, Long orderId) {
        orderService.findByIdAndUser(user, orderId);

        return ticketRepository.findByOrderItemOrderIdOrderByIdAsc(orderId).stream()
                .map(ticket -> TicketResponse.from(
                        ticket, ticketTokenSigner.sign(ticket.getId())))
                .toList();
    }

    /**
     * 預覽：這張票現在核銷的話會得到什麼結果？<b>不改任何狀態。</b>
     *
     * <p>⭐ 存在的理由：掃到之後要先讓工作人員確認「核銷王小明的一般票嗎」，
     * 而那個名字必須在<b>還沒核銷之前</b>就拿得到。
     * 沒有這一支的話，掃到就核銷了，根本沒有確認的機會。
     *
     * <p>⚠️ 這只是<b>預測</b>，不是保證。預覽說「會成功」之後，
     * 另一個工作人員可能搶先核銷 —— 真正的把關是 {@code checkIn} 裡的條件式 UPDATE。
     * 預覽是給人看的，不是給程式信的。
     */
    @Transactional(readOnly = true)
    public CheckInResponse preview(User organizer, Long eventId, String token) {
        eventService.getOwnedEntity(organizer, eventId);

        Optional<Ticket> found = findTicket(eventId, token);
        if (found.isEmpty()) {
            return CheckInResponse.invalid();
        }
        Ticket ticket = found.get();
        return CheckInResponse.of(predictedResult(ticket.getStatus()), ticket);
    }

    /**
     * 主辦者的核銷進度：「已入場 120 / 應到 300」，並且拆到票種。
     *
     * <p>⚠️ 授權沿用 {@code getOwnedEntity} —— 不是你的活動就 403，跟 {@code checkIn}
     * 完全一致。這裡是 403 不是 404：已發布的活動本來就是公開的，隱瞞存在沒有意義。
     *
     * <p>⭐ <b>兩句查詢，而且都跟票種數無關（O(1)）</b>：一句撈票種清單、一句 GROUP BY
     * 算數字。活動層的總數是<b>把票種層的數字加起來</b>得到的，
     * 刻意不為了它再發第三句聚合查詢。
     *
     * <p>⚠️ 為什麼票種清單要另外撈：{@code GROUP BY} 是從 tickets 出發的，
     * <b>一張都沒賣出去的票種不會出現在結果裡</b>。少了那一句，新建的票種會
     * 從主辦者的畫面上憑空消失 —— 看起來像壞掉，而不像「還沒賣出」。
     */
    @Transactional(readOnly = true)
    public CheckInProgressResponse progress(User organizer, Long eventId) {
        eventService.getOwnedEntity(organizer, eventId);

        // status -> 張數，依票種分組。⚠️ 只有「有票的組合」會在裡面
        Map<Long, Map<TicketStatus, Long>> counts = ticketRepository
                .countByTicketTypeAndStatus(eventId).stream()
                .collect(Collectors.groupingBy(
                        TicketStatusCount::ticketTypeId,
                        Collectors.toMap(TicketStatusCount::status, TicketStatusCount::count)));

        List<TicketTypeCheckInProgress> byTicketType = ticketTypeRepository
                .findByEventIdOrderByIdAsc(eventId).stream()
                .map(ticketType -> {
                    Map<TicketStatus, Long> byStatus =
                            counts.getOrDefault(ticketType.getId(), Map.of());
                    long used = byStatus.getOrDefault(TicketStatus.USED, 0L);
                    long valid = byStatus.getOrDefault(TicketStatus.VALID, 0L);
                    long voided = byStatus.getOrDefault(TicketStatus.VOID, 0L);
                    return new TicketTypeCheckInProgress(
                            ticketType.getId(), ticketType.getName(),
                            used, used + valid, voided);
                })
                .toList();

        // 活動層 = 票種層相加。⚠️ 不要改成另一句 GROUP BY ——
        // 兩句各自算的話，中間有票被核銷就會回傳對不起來的數字
        return new CheckInProgressResponse(
                byTicketType.stream().mapToLong(TicketTypeCheckInProgress::checkedIn).sum(),
                byTicketType.stream().mapToLong(TicketTypeCheckInProgress::expected).sum(),
                byTicketType.stream().mapToLong(TicketTypeCheckInProgress::voided).sum(),
                byTicketType);
    }

    /**
     * ⚠️ enum 的 switch 運算式有窮盡性檢查 ——
     * TicketStatus 之後多一個值，這裡編譯不過，逼你想清楚要怎麼顯示
     */
    private CheckInResponse.Result predictedResult(TicketStatus status) {
        return switch (status) {
            case VALID -> CheckInResponse.Result.SUCCESS;
            case USED -> CheckInResponse.Result.ALREADY_USED;
            case VOID -> CheckInResponse.Result.VOID;
        };
    }

    /** 驗簽 + 撈票。⚠️ 查詢範圍限定這個活動，所以別場的票撈不出來 */
    private Optional<Ticket> findTicket(Long eventId, String token) {
        return ticketTokenSigner.verify(token)
                .flatMap(ticketId -> ticketRepository.findByIdAndEventId(ticketId, eventId));
    }

    /**
     * 核銷一張票。
     *
     * <p>⚠️ 這支<b>不丟例外</b>（除了「這不是你的活動」）——
     * 「已經用過」「無效的票」對掃票的人來說是需要看到細節的結果，不是錯誤。
     */
    @Transactional
    public CheckInResponse checkIn(User organizer, Long eventId, String token) {
        // ① 這個活動是不是你的。不是的話丟 ResourceAccessDeniedException（403）。
        // ⚠️ 這裡是 403 不是 404 —— 已發布的活動本來就是公開的，
        // 隱瞞它的存在沒有意義。跟刪除評論是同一個判斷
        eventService.getOwnedEntity(organizer, eventId);

        // ② 驗簽。⚠️ 失敗不區分原因 —— 見 TicketTokenSigner
        Optional<Long> ticketId = ticketTokenSigner.verify(token);
        if (ticketId.isEmpty()) {
            return CheckInResponse.invalid();
        }

        // ③ 撈票。⭐ 查詢範圍限定這個活動，所以「別的活動的票」跟
        //    「票不存在」得到同一個結果，不需要另外寫檢查
        Optional<Ticket> found = ticketRepository.findByIdAndEventId(ticketId.get(), eventId);
        if (found.isEmpty()) {
            return CheckInResponse.invalid();
        }
        Ticket ticket = found.get();

        // ④ 原子性標記已使用
        int updated = ticketRepository.markUsed(ticket.getId(), Instant.now(), organizer);
        if (updated == 0) {
            // ⚠️ 讀到的 status 若還是 VALID，代表另一個工作人員在這一瞬間搶先核銷了。
            // 對現場的人來說結果一樣：這張票已經被用掉了
            //（那種情況下 usedAt 會是 null，前端顯示成「已被核銷」即可）
            return CheckInResponse.of(
                    ticket.getStatus() == TicketStatus.VOID
                            ? CheckInResponse.Result.VOID
                            : CheckInResponse.Result.ALREADY_USED,
                    ticket);
        }

        // ⚠️ ticket 是 markUsed 之前讀進來的，記憶體裡的 status 還是 VALID ——
        // 但回應只用得到票種名稱與持票人，那兩個不受這次更新影響
        return CheckInResponse.of(CheckInResponse.Result.SUCCESS, ticket);
    }
}
