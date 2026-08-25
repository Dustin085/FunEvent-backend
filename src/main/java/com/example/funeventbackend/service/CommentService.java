package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.comment.CommentResponse;
import com.example.funeventbackend.dto.comment.CreateCommentRequest;
import com.example.funeventbackend.dto.comment.MyCommentResponse;
import com.example.funeventbackend.dto.comment.RatingSummary;
import com.example.funeventbackend.exception.AlreadyCommentedException;
import com.example.funeventbackend.exception.CommentNotAllowedException;
import com.example.funeventbackend.exception.ResourceAccessDeniedException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Comment;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.CommentRepository;
import com.example.funeventbackend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CommentService {
    /** ⚠️ 這句是給「查自己對某活動的評論」用的，語意是「還沒評過」，不是「id 不存在」 */
    private static final String NOT_COMMENTED_MESSAGE = "尚未評論過這個活動";
    private static final String COMMENT_NOT_FOUND_MESSAGE = "找不到這則評論";

    private final CommentRepository commentRepository;
    private final OrderRepository orderRepository;
    private final EventService eventService;

    @Transactional
    public CommentResponse create(User user, Long eventId, CreateCommentRequest dto) {
        // getPublishedEntity 對未發布的活動回 404（不洩漏存在性）
        Event event = eventService.getPublishedEntity(eventId);

        // ⚠️ 用 startAt 不是 endAt：多日活動的 endAt 可能是一個月後，
        // 要等到那時才能評太久。「已經去過了」用開始時間判斷比較貼近實際
        if (Instant.now().isBefore(event.getStartAt())) {
            throw new CommentNotAllowedException("活動開始後才能評論");
        }
        // ⭐ 這條規則是「評分有意義」的前提 —— 沒買過的人不能評
        if (!orderRepository.hasPaidOrderForEvent(user.getId(), eventId)) {
            throw new CommentNotAllowedException("只有購票並完成付款的參加者可以評論");
        }

        try {
            Comment saved = commentRepository.save(Comment.builder()
                    .event(event)
                    .user(user)
                    .rating(dto.rating())
                    .content(dto.content())
                    .build());
            return CommentResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(event_id, user_id) 擋下的重複送出（或兩個分頁的併發）。
            // ⚠️ 這裡捕捉之後直接往外拋，不重試 —— 和 OAuth 第一次登入不同，
            // 那裡要「重查對方剛建好的資料」，這裡本來就該讓整個交易回滾
            throw new AlreadyCommentedException("你已經評論過這個活動了");
        }
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> findByEvent(Long eventId, Pageable pageable) {
        return commentRepository.findByEventId(eventId, pageable).map(CommentResponse::from);
    }

    @Transactional(readOnly = true)
    public RatingSummary getRatingSummary(Long eventId) {
        return commentRepository.findRatingSummary(eventId);
    }

    /** 會員中心的「我的評論」。不篩活動狀態 —— 那是自己的紀錄，活動下架了也還在 */
    @Transactional(readOnly = true)
    public Page<MyCommentResponse> findMine(User user, Pageable pageable) {
        return commentRepository.findByUserId(user.getId(), pageable)
                .map(MyCommentResponse::from);
    }

    @Transactional(readOnly = true)
    public CommentResponse findMyComment(User user, Long eventId) {
        return commentRepository.findByEventIdAndUserId(eventId, user.getId())
                .map(CommentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_COMMENTED_MESSAGE));
    }

    /**
     * 刪除自己的評論。
     *
     * <p>⭐ 硬刪不軟刪：刪掉之後 UNIQUE(event_id, user_id) 就解開了，
     * 使用者可以重新評一次 —— 這正是「改評論」的替代路徑（目前沒有修改端點）。
     * 軟刪的話那個限制還在，等於刪了卻不能重寫。
     *
     * <p>⚠️ 評分平均是 {@code findRatingSummary} 每次即時算的，
     * 沒有快取的聚合值需要失效，刪完自動就對了。
     */
    @Transactional
    public void delete(User user, Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException(COMMENT_NOT_FOUND_MESSAGE));

        // ⚠️ 這裡用 403 而不是「一律 404」—— 和訂單、草稿活動是相反的選擇。
        // 那些是私有資源，403 等於證實了這個 id 存在；
        // 但評論是公開的，任何人本來就看得到它存在，隱瞞沒有意義
        if (!comment.getUser().getId().equals(user.getId())) {
            throw new ResourceAccessDeniedException("沒有刪除這則評論的權限");
        }

        commentRepository.delete(comment);
    }
}
