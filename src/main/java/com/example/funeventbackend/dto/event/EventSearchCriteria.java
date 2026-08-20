package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;

import java.util.List;

/**
 * 活動搜尋的條件。
 *
 * <p>包成一個 record 而不是一路傳參數，是為了之後加條件
 *（日期區間、價格區間、tags）時，Service 與 Controller 的簽章不用一直改。
 *
 * <p>⚠️ 篩選語意：同一個欄位內是 OR（勾了音樂和運動＝兩者皆可），
 * 不同欄位之間是 AND（音樂類 且 在台北）。
 *
 * @param keyword    關鍵字，null 或空白代表不篩選
 * @param categories 分類，null 或空 List 代表不篩選
 * @param cities     縣市，null 或空 List 代表不篩選
 */
public record EventSearchCriteria(
        String keyword,
        List<Category> categories,
        List<City> cities
) {
}
