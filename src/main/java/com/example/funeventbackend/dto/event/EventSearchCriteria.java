package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;

/**
 * 活動搜尋的條件。
 *
 * <p>包成一個 record 而不是一路傳參數，是為了之後加條件
 *（日期區間、價格區間、tags）時，Service 與 Controller 的簽章不用一直改。
 *
 * @param keyword  關鍵字，null 或空白代表不篩選
 * @param category 分類，null 代表不篩選
 * @param city     縣市，null 代表不篩選
 */
public record EventSearchCriteria(
        String keyword,
        Category category,
        City city
) {
}
