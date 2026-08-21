package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.dto.event.EventSearchCriteria;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.dto.event.EventSummaryResponse;
import com.example.funeventbackend.dto.event.UpdateEventRequest;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {
    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> create(
            @Valid @RequestBody CreateEventRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.create(principal.getUser(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EventResponse> update(
            @Valid @RequestBody UpdateEventRequest request,
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long id
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.update(principal.getUser(), id, request));
    }

    @PatchMapping("/{id}/publish")
    public ResponseEntity<EventResponse> publish(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long id
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.publish(principal.getUser(), id));
    }

    /**
     * 取消活動。放在 publish 旁邊而不是 /api/organizers 底下 ——
     * ⚠️ 只有「讀取」需要分公開／擁有者兩種視角，寫入本來就一定要驗擁有權，
     * 不存在「公開的寫入」。
     */
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<EventResponse> cancel(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(eventService.cancel(principal.getUser(), id));
    }

    @GetMapping
    public ResponseEntity<PagedModel<EventSummaryResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Category> category,
            @RequestParam(required = false) List<City> city,
            @PageableDefault(size = 12, sort = "startAt", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        // size=12 好排格線；依 startAt 升冪 =「即將登場」，售票網站的預設語意。
        //
        // 多選：?category=A&category=B（Spring 也吃 ?category=A,B）。
        // 同一欄位內是 OR，不同欄位之間是 AND。
        // ⚠️ 傳認不得的值時 Spring 會拋 MethodArgumentTypeMismatchException，
        // GlobalExceptionHandler 已經把它處理成 400（而不是 500）；
        // 空字串會變成 List 裡的 null，由 EventSpecifications 濾掉
        EventSearchCriteria criteria = new EventSearchCriteria(q, category, city);
        return ResponseEntity.ok(new PagedModel<>(eventService.search(criteria, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> get(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.findById(id));
    }
}
