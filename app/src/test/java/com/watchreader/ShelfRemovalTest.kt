package com.watchreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删书命中判定：书架页按 uri、Web 端按文件名（忽略大小写）两条口径
 */
class ShelfRemovalTest {

    @Test
    fun testUriHitClosesOpenBook() {
        assertTrue(
            ShelfRemoval.hits(
                target = ShelfRemoval.RemovalTarget(
                    uriString = "content://books/a",
                    fileName = "a.txt",
                    compareFileName = false
                ),
                currentUriString = "content://books/a",
                currentFileName = "a.txt"
            )
        )
    }

    @Test
    fun testShelfDeletionIgnoresSameTitleDifferentDocument() {
        // 书架删的是另一份同书名的文档：当前阅读的这本书不能关
        assertFalse(
            ShelfRemoval.hits(
                target = ShelfRemoval.RemovalTarget(
                    uriString = "content://books/b",
                    fileName = "同名.txt",
                    compareFileName = false
                ),
                currentUriString = "content://books/a",
                currentFileName = "同名.txt"
            )
        )
    }

    @Test
    fun testWebDeletionMatchesFileNameIgnoringCase() {
        assertTrue(
            ShelfRemoval.hits(
                target = ShelfRemoval.RemovalTarget(
                    uriString = null,
                    fileName = "Novel.TXT",
                    compareFileName = true
                ),
                currentUriString = "content://books/x",
                currentFileName = "novel.txt"
            )
        )
    }

    @Test
    fun testNoOpenBookNeverHit() {
        assertFalse(
            ShelfRemoval.hits(
                target = ShelfRemoval.RemovalTarget(
                    uriString = "content://books/a",
                    fileName = "a.txt",
                    compareFileName = true
                ),
                currentUriString = null,
                currentFileName = ""
            )
        )
    }

    @Test
    fun testBlankNamesNeverMatch() {
        // 两端文件名都为空时不能判成命中
        assertFalse(
            ShelfRemoval.hits(
                target = ShelfRemoval.RemovalTarget(
                    uriString = null,
                    fileName = "",
                    compareFileName = true
                ),
                currentUriString = "content://books/a",
                currentFileName = ""
            )
        )
    }
}
