package com.example.funeventbackend.dto.city;

import com.example.funeventbackend.model.City;

/**
 * @param code 給程式用：?city= 的值
 * @param name 給人看。⚠️ 用 fullName（「新竹市」）而不是 shortName ——
 *             篩選選單裡必須能區分新竹市與新竹縣、嘉義市與嘉義縣，
 *             簡稱在這兩組上會撞
 */
public record CityResponse(String code, String name) {
    public static CityResponse from(City city) {
        return new CityResponse(city.name(), city.getFullName());
    }
}
