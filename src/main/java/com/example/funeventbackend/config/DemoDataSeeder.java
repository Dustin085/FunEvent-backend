package com.example.funeventbackend.config;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.Comment;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventImage;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.CommentRepository;
import com.example.funeventbackend.repository.EventImageRepository;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 開發用示範資料。H2 是記憶體資料庫，每次重啟都會清空 ——
 * 有了它，重啟後端就自動有一批可展示的活動，不必再開 Postman 建。
 *
 * <p>⚠️ 預設關閉（{@code @ConditionalOnProperty} 刻意不加 matchIfMissing）。
 * 它會建立密碼公開的帳號，在正式環境跑起來等於直接開後門。
 * 本機開發請在 run configuration 加環境變數 {@code DEMO_DATA=true}。
 */
@Component
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {
    private static final String DEMO_PASSWORD = "password123";
    private static final String ORGANIZER_EMAIL = "organizer@funevent.test";
    private static final String BUYER_EMAIL = "buyer@funevent.test";

    private final UserRepository userRepository;
    private final OrganizerRepository organizerRepository;
    private final EventRepository eventRepository;
    private final EventImageRepository eventImageRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CommentRepository commentRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 只在全新的資料庫上動作。萬一設定被誤開在有資料的環境，這是最後一道防線
        if (userRepository.count() > 0) {
            log.info("資料庫已有資料，略過示範資料建立");
            return;
        }

        User organizerUser = userRepository.save(User.builder()
                .email(ORGANIZER_EMAIL)
                .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
                .name("蘭響音樂教室")
                .role(RoleType.USER)
                .build());
        userRepository.save(User.builder()
                .email(BUYER_EMAIL)
                .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
                .name("示範買家")
                .role(RoleType.USER)
                .build());

        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(organizerUser)
                .name("蘭響音樂教室")
                .introduction("師資皆為從業多年的音樂教師，多數同時是樂團樂手或音樂製作人。"
                        + "我們也策劃親子共學、戶外體驗與手作課程。")
                .build());

        // 已經結束的那一場要拿來示範評論 —— 評論資格要求「活動已開始 + 有 PAID 訂單」
        Event pastEvent = null;
        TicketType pastEventTicket = null;

        for (DemoEvent demo : DEMO_EVENTS) {
            Event event = eventRepository.save(Event.builder()
                    .organizer(organizer)
                    .name(demo.name())
                    .description(demo.description())
                    .startAt(Instant.now().plus(demo.startInDays(), ChronoUnit.DAYS))
                    .endAt(Instant.now()
                            .plus(demo.startInDays(), ChronoUnit.DAYS)
                            .plus(demo.durationHours(), ChronoUnit.HOURS))
                    .category(demo.category())
                    .city(demo.city())
                    .district(demo.district())
                    .locationName(demo.locationName())
                    .address(demo.address())
                    // 直接建成已發布，不必再手動 publish
                    .status(EventStatus.PUBLISHED)
                    .build());

            // 圖片先指向前端的靜態檔。之後接物件儲存時只會換掉這個 URL，
            // 資料模型與前端都不用動
            int sortOrder = 0;
            for (String imageUrl : demo.imageUrls()) {
                eventImageRepository.save(EventImage.builder()
                        .event(event)
                        .imageUrl(imageUrl)
                        .sortOrder(sortOrder++)
                        .build());
            }

            TicketType firstTicket = null;
            for (DemoTicketType ticket : demo.ticketTypes()) {
                TicketType saved = ticketTypeRepository.save(TicketType.builder()
                        .event(event)
                        .name(ticket.name())
                        .description(ticket.description())
                        .price(new BigDecimal(ticket.price()))
                        .capacity(ticket.capacity())
                        .stock(ticket.capacity())
                        .build());
                if (firstTicket == null) {
                    firstTicket = saved;
                }
            }

            if (demo.startInDays() < 0) {
                pastEvent = event;
                pastEventTicket = firstTicket;
            }
        }

        if (pastEvent != null && pastEventTicket != null) {
            seedComments(pastEvent, pastEventTicket);
            // ⚠️ 這場活動不會出現在首頁與搜尋結果裡 —— 查詢會排除已結束的活動。
            // 要看評論得直接開網址，所以把 id 印出來
            log.warn("示範評論建在活動 id={}（已結束，故不會出現在列表中）：{}",
                    pastEvent.getId(), pastEvent.getName());
        }

        log.warn("已建立 {} 場示範活動。可登入帳號：{} / {}（買家）、{} / {}（主辦者）",
                DEMO_EVENTS.size(), BUYER_EMAIL, DEMO_PASSWORD, ORGANIZER_EMAIL, DEMO_PASSWORD);
    }

    /**
     * 幫已經開始的那場活動補上示範評論。
     *
     * <p>⚠️ 不能只塞 comments —— CommentService 要求「有這個活動的 PAID 訂單」，
     * 所以每位評論者都要有自己的使用者、訂單與明細。
     * 只塞評論的話資料庫裡會有「不可能透過 API 產生」的資料，
     * 那種示範資料會掩蓋掉規則本身的問題。
     *
     * <p>每人一個獨立帳號，因為 UNIQUE(event_id, user_id) 限制一人一則。
     */
    private void seedComments(Event event, TicketType ticketType) {
        for (DemoComment demo : DEMO_COMMENTS) {
            User reviewer = userRepository.save(User.builder()
                    .email(demo.email())
                    .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
                    .name(demo.name())
                    .role(RoleType.USER)
                    .build());

            Order order = orderRepository.save(Order.builder()
                    .user(reviewer)
                    .totalAmount(ticketType.getPrice())
                    .status(OrderStatusType.PAID)
                    .paidAt(Instant.now().minus(20, ChronoUnit.DAYS))
                    // 早就付完款了，期限不再有意義，但欄位是 NOT NULL
                    .expiresAt(Instant.now().minus(20, ChronoUnit.DAYS))
                    .build());
            orderItemRepository.save(OrderItem.builder()
                    .order(order)
                    .ticketType(ticketType)
                    .ticketTypeName(ticketType.getName())
                    .unitPrice(ticketType.getPrice())
                    .quantity(1)
                    .build());
            // 賣掉了就要扣庫存，否則示範資料自己就是不一致的。
            // ticketType 是這個交易裡的 managed entity，髒檢查會寫回去
            ticketType.setStock(ticketType.getStock() - 1);

            commentRepository.save(Comment.builder()
                    .event(event)
                    .user(reviewer)
                    .rating(demo.rating())
                    .content(demo.content())
                    .build());
        }
    }

    private record DemoComment(String email, String name, int rating, String content) {
    }

    private static final List<DemoComment> DEMO_COMMENTS = List.of(
            new DemoComment("reviewer1@funevent.test", "陳小姐", 5,
                    "老師超有耐心，我完全零基礎，三個小時真的彈完一首歌了。"
                            + "教室裡的樂器也都保養得很好，不會有那種弦鏽掉的狀況。"
                            + "唯一建議是可以多留一點時間讓大家互相彈給對方聽。"),
            new DemoComment("reviewer2@funevent.test", "王先生", 4,
                    "帶小孩一起去的，場地乾淨，動線也清楚。"
                            + "課程節奏對大人剛好，小朋友後半段稍微跟不上，"
                            + "如果能分齡分組會更好。"),
            new DemoComment("reviewer3@funevent.test", "林同學", 5,
                    "本來只是想找個週末的活動打發時間，結果整個被燒到，回家立刻買了一把。"));

    private record DemoTicketType(String name, String description, String price, int capacity) {
    }

    private record DemoEvent(
            String name,
            String description,
            int startInDays,
            int durationHours,
            Category category,
            City city,
            String district,
            String locationName,
            String address,
            List<String> imageUrls,
            List<DemoTicketType> ticketTypes
    ) {
    }

    /** 文案與圖片取自舊專案的 assets/json 與 json備份0904 */
    private static final List<DemoEvent> DEMO_EVENTS = List.of(
            new DemoEvent(
                    "流行音樂與民謠吉他－第一堂吉他課的首選",
                    """
                            民謠吉他是近幾十年來最流行的樂器之一，便宜好學又方便攜帶，\
                            是孩子探索音樂、創造音樂最好的一條途徑。

                            不用小提琴或國樂器的昂貴價格，也不用鋼琴的超大佔位。\
                            一把吉他揹著就能到處彈到處唱，您也可以在課程中和孩子一同學習，\
                            創造屬於親子之間的特別回憶。

                            【活動流程】
                            民謠吉他的特色 → 經典四和弦 → 簡單樂理 → 彈唱練習

                            【師資介紹】
                            蘭響音樂教室的師資皆為從業多年的音樂教師，\
                            大多同時是樂團樂手或音樂製作人。""",
                    9, 3,
                    Category.MUSIC_GROOVE, City.TAIPEI, "中山區",
                    "蘭響音樂教室",
                    "台北市中山區南京東路二段100號5樓",
                    List.of(
                            "/images/events/guitar-01.jpg",
                            "/images/events/guitar-02.jpg",
                            "/images/events/guitar-03.jpg",
                            "/images/events/guitar-04.jpg"),
                    List.of(
                            new DemoTicketType("單人體驗票", "含吉他租借", "690.00", 20),
                            new DemoTicketType("親子共學票", "一大一小，兩把吉他", "1200.00", 12))),
            new DemoEvent(
                    "【夏日營隊】小小棋神就是你！",
                    """
                            從圍棋的基本規則開始，用對局培養專注力與沉穩的性格。

                            採小班制，每四位學員配一位指導老師，\
                            當天會依程度分組，完全沒接觸過也能參加。

                            營隊最後有分組友誼賽，每位學員都會拿到結業證書。""",
                    16, 6,
                    Category.LIFE_EXPERIENCE, City.NEW_TAIPEI, "永和區",
                    "永和社區活動中心",
                    "新北市永和區永和路二段58號",
                    List.of("/images/events/chess-01.jpg"),
                    List.of(
                            new DemoTicketType("單日營隊票", "含教材與午餐", "1200.00", 24))),
            new DemoEvent(
                    "【小小畢卡索】走進色彩的繽紛世界",
                    """
                            不教技法，只帶孩子認識顏色。

                            從調色盤開始，讓孩子自己混出想要的顏色，\
                            再畫上一整面兩公尺寬的大畫布。

                            穿舊衣服來，我們準備了圍裙但一定會弄髒。""",
                    23, 3,
                    Category.ART_CULTURE, City.TAIPEI, "南港區",
                    "南港親子藝術空間",
                    "台北市南港區市民大道七段8號",
                    List.of("/images/events/painting-01.jpg"),
                    List.of(
                            new DemoTicketType("兒童單人票", "4–10 歲，需家長陪同", "580.00", 30))),
            new DemoEvent(
                    "甜蜜創作之旅：親子糖畫藝術體驗與手作工作坊",
                    """
                            糖畫是華人的傳統技藝，用融化的糖漿在石板上一筆畫出圖案。

                            老師會先示範龍、鳳、蝴蝶三種經典圖樣，\
                            接著每組自己動手，完成的作品可以直接吃掉或帶回家。

                            現場備有降溫設備，夏天參加也不會太熱。""",
                    31, 3,
                    Category.CREATIVE_DIY, City.NEW_TAIPEI, "三峽區",
                    "三峽老街手作街屋",
                    "新北市三峽區民權街84號",
                    List.of("/images/events/sugar-01.jpg"),
                    List.of(
                            new DemoTicketType("親子套票", "一大一小", "760.00", 20),
                            new DemoTicketType("加購親子票", "第二位孩童加購", "300.00", 20))),
            new DemoEvent(
                    "【夏日營隊】戶外平衡競走大挑戰，四大主題等你來挑戰！",
                    """
                            四個關卡、四種平衡挑戰，分組競賽計時。

                            從獨木橋、踏石過河到繩索橋，難度由淺入深，\
                            全程有教練在旁確保安全，並配戴護具。

                            雨天改於室內場地舉行，不取消。""",
                    40, 7,
                    Category.SPORT, City.NEW_TAIPEI, "板橋區",
                    "板橋第二運動場",
                    "新北市板橋區中山路一段1號",
                    List.of("/images/events/balance-01.jpg"),
                    List.of(
                            new DemoTicketType("單人挑戰票", "含護具租借與保險", "890.00", 40))),
            new DemoEvent(
                    "【田野大調查】走進鄉間大發現，我們吃的米從何而來？",
                    """
                            從一粒種子到一碗飯，走一趟完整的稻米旅程。

                            上午下田插秧（會弄濕，請帶替換衣物），\
                            午餐吃當地的割稻飯，下午做米食手作。

                            由在地小農帶領，全程約需步行兩公里。""",
                    54, 8,
                    Category.NATURE_SCIENCE, City.YILAN, "員山鄉",
                    "員山有機稻田",
                    "宜蘭縣員山鄉尚德路100號",
                    List.of("/images/events/field-01.jpg"),
                    List.of(
                            new DemoTicketType("成人票", "含午餐與手作材料", "1180.00", 30),
                            new DemoTicketType("孩童票", "6–12 歲", "780.00", 30))),
            // ⚠️ startInDays 是負的 —— 這場已經結束了。
            // 存在的理由只有一個：評論資格要求「活動已開始」，
            // 沒有一場過去的活動就沒辦法示範評論區長什麼樣子。
            // 代價是它不會出現在首頁與搜尋結果裡（查詢會排除已結束的活動）
            new DemoEvent(
                    "【回顧場】烏克麗麗一日速成班",
                    """
                            四弦、小巧、好上手 —— 烏克麗麗大概是最容易在一天內\
                            彈完一整首歌的樂器。

                            課程從調音、基本三和弦到完整彈唱，\
                            結束時每個人都會有一段自己的錄音帶回家。

                            【師資介紹】
                            由蘭響音樂教室的專任教師授課，現場提供樂器，空手來即可。""",
                    -21, 3,
                    Category.MUSIC_GROOVE, City.TAIPEI, "中山區",
                    "蘭響音樂教室",
                    "台北市中山區南京東路二段100號5樓",
                    List.of("/images/events/guitar-02.jpg"),
                    List.of(
                            new DemoTicketType("單人體驗票", "含樂器租借", "690.00", 20))));
}
