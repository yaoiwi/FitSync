package com.fitsync.data.health

import com.garmin.fit.GarminProduct

/**
 * 型号名 → Garmin product id。
 *
 * 有些区域的设备接口只下发型号名、不给 `productId`，而 monitoring FIT 需要它来
 * 标识设备。这里的数值全部引用 FIT SDK 的 [GarminProduct] 常量，不手写魔数，
 * SDK 升级时自动跟着变。
 *
 * 只在接口拿不到、源账号也补不到时才用，属于最后的兜底。
 */
object DeviceProducts {

    private val known: Map<String, Int> = mapOf(
        "FR255" to GarminProduct.FR255,
        "FR255MUSIC" to GarminProduct.FR255_MUSIC,
        "FR255SMALL" to GarminProduct.FR255_SMALL,
        "FR255SMALLMUSIC" to GarminProduct.FR255_SMALL_MUSIC,
        "FR265LARGE" to GarminProduct.FR265_LARGE,
        "FR265SMALL" to GarminProduct.FR265_SMALL,
        "FR955" to GarminProduct.FR955,
        "FR965" to GarminProduct.FR965,
        "FR165" to GarminProduct.FR165,
        "FR165MUSIC" to GarminProduct.FR165_MUSIC,
    )

    /** 设备名里的写法与 SDK 常量名对不上的，先归一到 [known] 的键 */
    private val aliases: Map<String, String> = mapOf(
        "FR265" to "FR265LARGE",
        "FR255S" to "FR255SMALL",
        "FR255SMUSIC" to "FR255SMALLMUSIC",
        "FR265S" to "FR265SMALL",
        "FR165M" to "FR165MUSIC",
    )

    /**
     * `Forerunner 255` → 3992，`Forerunner 255 Music` → 3990。
     * 认不出来返回 0，调用方保持“产品 ID 未知”。
     */
    fun byName(rawName: String): Int {
        if (rawName.isBlank()) return 0
        val key = rawName.uppercase()
            .replace("FORERUNNER", "FR")
            .replace(Regex("[^A-Z0-9]+"), "")
        return known[aliases[key] ?: key] ?: 0
    }
}
