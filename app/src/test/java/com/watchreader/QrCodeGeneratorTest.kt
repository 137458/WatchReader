package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun testQrMatrixGenerationForValidUrl() {
        val url = "http://192.168.1.102:8888"
        val size = 200
        val matrix = QrCodeGenerator.encodeToBitMatrix(url, size, size)

        assertNotNull(matrix)
        assertEquals(size, matrix.width)
        assertEquals(size, matrix.height)

        // 验证二维码中既有黑色模块也有白色模块
        var hasBlack = false
        var hasWhite = false
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (matrix.get(x, y)) {
                    hasBlack = true
                } else {
                    hasWhite = true
                }
                if (hasBlack && hasWhite) break
            }
            if (hasBlack && hasWhite) break
        }

        assertTrue("二维码矩阵必须包含黑色前景色模块", hasBlack)
        assertTrue("二维码矩阵必须包含白色背景色模块", hasWhite)
    }

    @Test
    fun testQrMatrixEmptyContentThrows() {
        try {
            QrCodeGenerator.encodeToBitMatrix("", 100, 100)
            fail("空内容应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("empty") == true)
        }
    }

    @Test
    fun testQrMatrixInvalidDimensionsThrows() {
        try {
            QrCodeGenerator.encodeToBitMatrix("http://test", -1, 100)
            fail("非法尺寸应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("positive") == true)
        }
    }
}
