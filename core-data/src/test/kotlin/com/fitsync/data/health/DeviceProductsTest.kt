package com.fitsync.data.health

import org.junit.Assert.assertEquals
import org.junit.Test

/** 型号名 → product id：接口不下发 PID 时的最后兜底。 */
class DeviceProductsTest {

    @Test
    fun `常见型号能按名字认出来`() {
        assertEquals(3992, DeviceProducts.byName("Forerunner 255"))
        assertEquals(3990, DeviceProducts.byName("Forerunner 255 Music"))
        assertEquals(3993, DeviceProducts.byName("Forerunner 255S"))
        assertEquals(4315, DeviceProducts.byName("Forerunner 965"))
        assertEquals(4024, DeviceProducts.byName("Forerunner 955"))
    }

    @Test
    fun `认不出来的型号返回 0`() {
        assertEquals(0, DeviceProducts.byName(""))
        assertEquals(0, DeviceProducts.byName("Venu 3"))
        assertEquals(0, DeviceProducts.byName("Edge 1040"))
    }
}
