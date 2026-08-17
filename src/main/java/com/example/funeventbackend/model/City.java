package com.example.funeventbackend.model;

import lombok.Getter;

/**
 * 台灣 22 個縣市。
 * <p>
 * 用 enum 而不是自由文字，是為了「按地區找活動」這個功能 ——
 * 字串會讓「台北」「臺北」「台北市」變成三筆不同的資料，之後得回頭清洗。
 * <p>
 * 鄉鎮市區（368 個）則維持自由文字：它只是顯示用，不是查詢的依據。
 */
@Getter
public enum City {
    KEELUNG("基隆市", "基隆"),
    TAIPEI("臺北市", "台北"),
    NEW_TAIPEI("新北市", "新北"),
    TAOYUAN("桃園市", "桃園"),
    // 新竹市／新竹縣、嘉義市／嘉義縣的簡稱會撞，保留後綴
    HSINCHU_CITY("新竹市", "新竹市"),
    HSINCHU_COUNTY("新竹縣", "新竹縣"),
    MIAOLI("苗栗縣", "苗栗"),
    TAICHUNG("臺中市", "台中"),
    CHANGHUA("彰化縣", "彰化"),
    NANTOU("南投縣", "南投"),
    YUNLIN("雲林縣", "雲林"),
    CHIAYI_CITY("嘉義市", "嘉義市"),
    CHIAYI_COUNTY("嘉義縣", "嘉義縣"),
    TAINAN("臺南市", "台南"),
    KAOHSIUNG("高雄市", "高雄"),
    PINGTUNG("屏東縣", "屏東"),
    YILAN("宜蘭縣", "宜蘭"),
    HUALIEN("花蓮縣", "花蓮"),
    TAITUNG("臺東縣", "台東"),
    PENGHU("澎湖縣", "澎湖"),
    KINMEN("金門縣", "金門"),
    LIENCHIANG("連江縣", "連江");

    /**
     * -- GETTER --
     * 正式名稱，例如「新北市」
     */
    private final String fullName;
    /**
     * -- GETTER --
     * 卡片上顯示的簡稱，例如「新北」
     */
    private final String shortName;

    City(String fullName, String shortName) {
        this.fullName = fullName;
        this.shortName = shortName;
    }
}
