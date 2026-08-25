package com.example.funeventbackend.repository;

import com.example.funeventbackend.dto.comment.RatingSummary;
import com.example.funeventbackend.model.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * ⚠️ @EntityGraph 預抓 user，否則列表上每一則評論都會為了顯示名稱多發一句 SQL。
     * 抓的是 @ManyToOne，JOIN 後列數不變，分頁的 LIMIT 仍然正確。
     */
    @EntityGraph(attributePaths = "user")
    Page<Comment> findByEventId(Long eventId, Pageable pageable);

    /** 查「這個人對這個活動」有沒有評論過。用來支援前端的已評論過狀態 */
    Optional<Comment> findByEventIdAndUserId(Long eventId, Long userId);

    /**
     * 會員中心的「我的評論」。
     *
     * <p>⚠️ @EntityGraph 預抓 event —— 列表要顯示活動名稱，
     * 否則每一則都會為了那個名稱多發一句 SQL。抓的是 @ManyToOne，
     * JOIN 後列數不變，分頁的 LIMIT 仍然正確。
     */
    @EntityGraph(attributePaths = "event")
    Page<Comment> findByUserId(Long userId, Pageable pageable);

    /**
     * 平均分與則數，一次查完。
     *
     * <p>用 JPQL 的建構式查詢直接組成 DTO，而不是回 Object[] 讓呼叫端自己轉型 ——
     * ⚠️ 這樣 EventService 與 CommentService 都能直接用這個 repository，
     * 不需要互相依賴。（EventService 呼叫 CommentService、CommentService 又要
     * 呼叫 EventService 取活動，會變成循環依賴讓 Spring 啟動失敗。）
     *
     * <p>⚠️ 沒有任何評論時 AVG 回傳 null（不是 0）——
     * RatingSummary.average 因此是 Double 而不是 double。
     */
    @Query("SELECT new com.example.funeventbackend.dto.comment.RatingSummary("
            + "AVG(c.rating), COUNT(c)) "
            + "FROM Comment c WHERE c.event.id = :eventId")
    RatingSummary findRatingSummary(@Param("eventId") Long eventId);
}
