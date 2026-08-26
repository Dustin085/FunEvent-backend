package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.comment.CommentEligibilityResponse;
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
import java.util.Optional;

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

        // ⭐ 資格判斷跟 checkEligibility 共用同一段 —— 各寫一次的話，
        // 後端內部就會有兩份會走鐘的規則，比前端複製一份更糟
        //（兩份都在後端、看起來都很權威）
        findBlockingReason(user, event).ifPresent(reason -> {
            throw switch (reason) {
                case NOT_STARTED -> new CommentNotAllowedException("活動開始後才能評論");
                case NOT_ATTENDED ->
                        new CommentNotAllowedException("只有購票並完成付款的參加者可以評論");
                case ALREADY_COMMENTED -> new AlreadyCommentedException("你已經評論過這個活動了");
            };
        });

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
            //
            // ⚠️ 這段**絕對不能**因為上面已經先查過 ALREADY_COMMENTED 就拿掉：
            // 那個查詢只是為了給出漂亮的錯誤，真正擋住併發的是資料庫的唯一約束。
            // 兩個分頁同時送出時，兩邊的查詢都會回「還沒評過」，
            // 少了這個 catch 其中一個就會爆成 500。
            //
            // ⚠️ 捕捉之後直接往外拋，不重試 —— 和 OAuth 第一次登入不同，
            // 那裡要「重查對方剛建好的資料」，這裡本來就該讓整個交易回滾
            throw new AlreadyCommentedException("你已經評論過這個活動了");
        }
    }

    /**
     * 「我現在能不能評論這個活動」。前端用它決定要顯示表單還是說明。
     *
     * <p>⚠️ 這支<b>不丟例外</b>：「不能評論」是正常的查詢結果，不是錯誤。
     */
    @Transactional(readOnly = true)
    public CommentEligibilityResponse checkEligibility(User user, Long eventId) {
        Event event = eventService.getPublishedEntity(eventId);
        return findBlockingReason(user, event)
                .map(CommentEligibilityResponse::blockedBy)
                .orElseGet(CommentEligibilityResponse::allowed);
    }

    /**
     * 不能評論的原因，空的代表可以。{@code create} 與 {@code checkEligibility} 共用。
     *
     * <p>⚠️ 用 startAt 不是 endAt：多日活動的 endAt 可能是一個月後，
     * 要等到那時才能評太久。「已經去過了」用開始時間判斷比較貼近實際。
     *
     * <p>⭐ 「只有買過票的人能評」是整個評分有意義的前提 ——
     * 任何人都能評的話，分數只反映誰有空刷，不再反映參加者的體驗。
     */
    private Optional<CommentEligibilityResponse.Reason> findBlockingReason(
            User user, Event event) {
        if (Instant.now().isBefore(event.getStartAt())) {
            return Optional.of(CommentEligibilityResponse.Reason.NOT_STARTED);
        }
        if (!orderRepository.hasPaidOrderForEvent(user.getId(), event.getId())) {
            return Optional.of(CommentEligibilityResponse.Reason.NOT_ATTENDED);
        }
        if (commentRepository.findByEventIdAndUserId(event.getId(), user.getId()).isPresent()) {
            return Optional.of(CommentEligibilityResponse.Reason.ALREADY_COMMENTED);
        }
        return Optional.empty();
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
