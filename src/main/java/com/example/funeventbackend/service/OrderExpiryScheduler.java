package com.example.funeventbackend.service;

import com.example.funeventbackend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 定期把逾時未付款的訂單取消掉，並把票還回去。
 *
 * <p>沒有這個排程的話，使用者建了訂單卻不付款，那些票就被永久鎖住 ——
 * 活動可以「賣完」但實際上一張都沒賣出去。
 *
 * <p>⚠️ 為什麼一定要排程，不能在查詢時判斷「這筆已逾時」就好：
 * 我們要的是**庫存回到可賣狀態**，不只是把訂單顯示成已取消。
 * 沒有人真的去寫回 stock，票就永遠拿不回來。
 */
@Component
// ⚠️ 測試環境要關掉：排程在測試跑到一半時取消訂單，會讓斷言看起來隨機失敗
@ConditionalOnProperty(name = "app.order.expiry.enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OrderExpiryScheduler {
    private static final int BATCH_SIZE = 100;
    /** 單次執行最多處理幾批，避免積壓時一次跑太久佔住排程執行緒 */
    private static final int MAX_BATCHES_PER_RUN = 10;

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    /**
     * ⚠️ fixedDelay 不是 fixedRate：
     * fixedRate 是「每 N 毫秒觸發一次」，前一次還沒跑完下一次就會疊上來；
     * fixedDelay 是「上一次結束後再等 N 毫秒」，永遠不會重疊。
     *
     * <p>多實例部署不需要分散式鎖 —— 兩個實例掃到同一筆訂單時，
     * markCancelled 的條件式 UPDATE 只會有一個回傳 1，另一個什麼都不做。
     */
    @Scheduled(fixedDelayString = "${app.order.expiry.scan-interval:60s}")
    public void cancelExpiredOrders() {
        Instant now = Instant.now();
        int cancelled = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            // 每一輪都重查：上一批處理完就不再是 PENDING，自然會取到新的一批
            List<Long> ids = orderRepository.findExpiredPendingIds(
                    now, PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }

            for (Long id : ids) {
                try {
                    // ⚠️ 跨 bean 呼叫才會經過 AOP 代理，每一筆各自一個交易 ——
                    // 一筆失敗不會把整批已完成的取消一起回滾
                    if (orderService.cancelExpiredOrder(id)) {
                        cancelled++;
                    }
                } catch (Exception e) {
                    // 單筆失敗不該讓整批停擺，記下來繼續處理下一筆
                    log.error("取消逾時訂單失敗，跳過 orderId={}", id, e);
                }
            }

            // 沒滿一批代表已經清完了
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }

        if (cancelled > 0) {
            log.info("本次取消了 {} 筆逾時訂單", cancelled);
        }
    }
}
