package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.city.CityResponse;
import com.example.funeventbackend.model.City;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/cities")
public class CityController {

    /**
     * 22 個縣市的清單。前端的地區篩選從這裡拿 ——
     * 縣市名稱只存在後端一份，不必在前端再維護一份對照表。
     *
     * <p>順帶一提，它同時是前端驗證 ?city= 的白名單來源。
     */
    @GetMapping
    public ResponseEntity<List<CityResponse>> list() {
        return ResponseEntity.ok(
                Arrays.stream(City.values()).map(CityResponse::from).toList());
    }
}
