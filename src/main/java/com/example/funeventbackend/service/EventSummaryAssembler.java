package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.EventSummaryResponse;
import com.example.funeventbackend.dto.event.EventTicketAggregate;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把一頁 {@link Event} 組成給卡片用的 {@link EventSummaryResponse}。
 *
 * <p>⭐ 抽出來的理由不只是「兩個地方要用」（搜尋與我的收藏），而是
 * <b>這段藏著一條很容易違反的規則</b>：票種聚合必須「一句查完整頁」。
 * 散在各處遲早有人寫成逐筆查詢，而 1+N 不會報錯，只會讓首頁慢慢變慢。
 */
@Component
@RequiredArgsConstructor
public class EventSummaryAssembler {
    private final TicketTypeRepository ticketTypeRepository;

    public Page<EventSummaryResponse> assemble(Page<Event> events) {
        Map<Long, EventTicketAggregate> aggregates = loadTicketAggregates(events.getContent());
        return events.map(event ->
                EventSummaryResponse.from(event, aggregates.get(event.getId())));
    }

    /**
     * 這一頁所有活動的票種聚合（最低價、剩餘張數），一句查完。
     *
     * <p>⚠️ 絕對不能改成「每個活動各查一次」—— 那就是 1+N，
     * 而首頁與搜尋頁是全站流量最高的端點。
     * {@code EventQuerySqlCountTest} 的句數斷言就是這件事的保護網。
     *
     * <p>⚠️ 沒有把「販售期間內」放進條件：那會讓聚合結果隨時間變動，
     * 而卡片上的「NT$ X 起」講的是這個活動的票價，不是「此刻能不能買」。
     * 能不能買是詳情頁的事（見前端的 resolveUnavailableReason）。
     */
    private Map<Long, EventTicketAggregate> loadTicketAggregates(List<Event> events) {
        if (events.isEmpty()) {
            // ⚠️ 空清單要提早返回 —— IN () 在多數資料庫是無效語法
            return Map.of();
        }
        List<Long> eventIds = events.stream().map(Event::getId).toList();
        return ticketTypeRepository.findAggregatesByEventIds(eventIds).stream()
                .collect(Collectors.toMap(EventTicketAggregate::eventId, aggregate -> aggregate));
    }
}
