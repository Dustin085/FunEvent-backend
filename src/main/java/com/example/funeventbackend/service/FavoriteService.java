package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.EventSummaryResponse;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.Favorite;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.FavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FavoriteService {
    private final FavoriteRepository favoriteRepository;
    private final EventService eventService;
    // 卡片用的摘要（含票種聚合），跟 EventService.search 共用
    private final EventSummaryAssembler eventSummaryAssembler;

    @Transactional
    public void create(User user, Long eventId) {
        Event event = eventService.getPublishedEntity(eventId);
        Favorite favorite = Favorite.builder()
                .user(user)
                .event(event)
                .build();
        try {
            favoriteRepository.save(favorite);
        } catch (DataIntegrityViolationException e) {
            // 已經收藏過了 —— PUT 的語意是「我要它變成這個狀態」，
            // 而那個狀態已經達成，這是成功不是錯誤。
            //
            // ⚠️ 不能改成「先查再存」：兩個分頁同時按收藏，兩邊的查詢都會回
            // 「還沒收藏」。真正擋住併發的是資料庫的 UNIQUE 約束，
            // 這個 catch 就是在接它（理由同 CommentService.create，只是結論相反）
        }
    }

    /**
     * ⭐ 查詢限定了 user_id，所以<b>碰不到別人的資料</b> ——
     * 不需要另外做擁有權檢查。「能不能授權錯」跟「有沒有記得檢查」無關，
     * 而是「查詢有沒有可能撈到別人的東西」。
     *
     * <p>⚠️ 刪到 0 列不是錯誤：目標狀態（沒有收藏）已經達成，controller 一樣回 204。
     */
    @Transactional
    public void delete(User user, Long eventId) {
        favoriteRepository.deleteByUserIdAndEventId(user.getId(), eventId);
    }

    /** 活動詳情頁用它決定收藏鈕要顯示成已收藏還是未收藏 */
    @Transactional(readOnly = true)
    public boolean isFavorited(User user, Long eventId) {
        return favoriteRepository.existsByUserIdAndEventId(user.getId(), eventId);
    }

    /**
     * 我的收藏。
     *
     * <p>⭐ 直接回 {@link EventSummaryResponse} 而不包一層自己的 DTO ——
     * 收藏列表的內容<b>就是活動摘要</b>，跟首頁、搜尋頁顯示的是同一種東西，
     * 前端可以原封不動重用 EventCard。
     *
     * <p>（對照 {@code MyCommentResponse} 為什麼需要自訂：那裡的形狀真的不同，
     * 公開列表要 userName、我的評論要 eventName。收藏沒有這種差異。）
     *
     * <p>⚠️ 收藏時間刻意不回傳：排序用得到（controller 依 createdAt 遞減），
     * 但顯示用不到。要顯示就得包一層，那才需要新 DTO。
     */
    @Transactional(readOnly = true)
    public Page<EventSummaryResponse> findMine(User user, Pageable pageable) {
        // Page.map 保留分頁資訊（總筆數、頁碼），只換內容的型別
        return eventSummaryAssembler.assemble(
                favoriteRepository.findByUserId(user.getId(), pageable)
                        .map(Favorite::getEvent));
    }
}
