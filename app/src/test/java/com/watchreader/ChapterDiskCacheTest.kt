package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class ChapterDiskCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("chapter_index")
    }

    @Test
    fun testSaveAndLoadVersion2WithTotalChars() {
        val chapters = listOf(
            Chapter(index = 0, title = "第一章 初始", charOffset = 0),
            Chapter(index = 1, title = "第二章 进阶", charOffset = 5000),
            Chapter(index = 2, title = "第三章 破境", charOffset = 12000)
        )
        val expectedTotalChars = 35000
        val key = "file://book1.txt_102400"

        // 保存 V2 缓存
        ChapterDiskCache.save(cacheDir, key, chapters, expectedTotalChars)

        // 读取 V2 缓存
        val result = ChapterDiskCache.load(cacheDir, key)
        assertNotNull("V2 缓存应能成功读取", result)
        assertEquals(expectedTotalChars, result!!.totalChars)
        assertEquals(3, result.chapters.size)
        assertEquals("第一章 初始", result.chapters[0].title)
        assertEquals(0, result.chapters[0].charOffset)
        assertEquals("第二章 进阶", result.chapters[1].title)
        assertEquals(5000, result.chapters[1].charOffset)
        assertEquals("第三章 破境", result.chapters[2].title)
        assertEquals(12000, result.chapters[2].charOffset)
    }

    @Test
    fun testVersion1BackwardCompatibilityReturnsNull() {
        // 模拟写入 Version 1 二进制缓存
        val magic = 0x57524349
        val version1 = 1
        val key = "legacy_v1_book"
        val hashKey = ChapterDiskCache.hashKey(key)
        val cacheFile = File(cacheDir, "$hashKey.idx")

        DataOutputStream(BufferedOutputStream(FileOutputStream(cacheFile))).use { dos ->
            dos.writeInt(magic)
            dos.writeInt(version1)
            dos.writeInt(2) // count
            dos.writeInt(0) // ch0 index
            dos.writeInt(0) // ch0 offset
            dos.writeUTF("旧版第一章")
            dos.writeInt(1) // ch1 index
            dos.writeInt(3000) // ch1 offset
            dos.writeUTF("旧版第二章")
            dos.flush()
        }

        assertTrue("V1 缓存文件必须存在", cacheFile.exists())

        // 读取旧版 V1 缓存：必须安全返回 null，不抛异常，且清理旧版坏文件
        val result = ChapterDiskCache.load(cacheDir, key)
        assertNull("检测到 Version 1 缓存时，应安全返回 null", result)
        assertTrue("旧版 V1 文件应被安全清理以便重建 V2", !cacheFile.exists())
    }

    @Test
    fun testCorruptedFileReturnsNull() {
        val key = "corrupted_book"
        val hashKey = ChapterDiskCache.hashKey(key)
        val cacheFile = File(cacheDir, "$hashKey.idx")

        // 写入非法魔数
        DataOutputStream(BufferedOutputStream(FileOutputStream(cacheFile))).use { dos ->
            dos.writeInt(0x11223344)
            dos.writeInt(2)
            dos.writeInt(100)
            dos.writeInt(1)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeUTF("异常章节")
        }

        val result = ChapterDiskCache.load(cacheDir, key)
        assertNull("魔数不匹配时应返回 null", result)
    }

    @Test
    fun testEmptyKeyOrChapters() {
        ChapterDiskCache.save(cacheDir, "", listOf(Chapter(0, "标题", 0)), 100)
        assertNull(ChapterDiskCache.load(cacheDir, ""))

        val key = "empty_chapters_key"
        ChapterDiskCache.save(cacheDir, key, emptyList(), 100)
        assertNull(ChapterDiskCache.load(cacheDir, key))
    }

    @Test
    fun testSaveReportsSuccessAndRejectsEmptyInput() {
        val chapters = listOf(Chapter(0, "第一章", 0), Chapter(1, "第二章", 3000))

        assertTrue("正常入参应报告落盘成功", ChapterDiskCache.save(cacheDir, "ok_key", chapters, 9000))
        assertFalse("空 key 应报告未落盘", ChapterDiskCache.save(cacheDir, "", chapters, 9000))
        assertFalse("空章节表应报告未落盘", ChapterDiskCache.save(cacheDir, "empty_key", emptyList(), 9000))
    }

    @Test
    fun testSaveOverwritesPreviousCacheWithoutLeavingTempFiles() {
        val key = "overwrite_book"
        ChapterDiskCache.save(cacheDir, key, listOf(Chapter(0, "旧章", 0)), 100)
        ChapterDiskCache.save(cacheDir, key, listOf(Chapter(0, "新章一", 0), Chapter(1, "新章二", 50)), 777)

        val loaded = ChapterDiskCache.load(cacheDir, key)
        assertNotNull("覆盖写后必须能读回最新内容", loaded)
        assertEquals(777, loaded!!.totalChars)
        assertEquals(2, loaded.chapters.size)
        assertEquals("新章一", loaded.chapters[0].title)

        // 原子替换不得留下临时文件：残留会让缓存目录无限增长
        val leftovers = cacheDir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("落盘后不得残留临时文件，实得 ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun testLoadOutcomeReportsMissingCacheWithoutDiscard() {
        val outcome = ChapterDiskCache.loadWithOutcome(cacheDir, "never_saved")

        assertNull(outcome.data)
        assertFalse("无缓存不属于丢弃坏档", outcome.discardedCorrupt)
    }

    @Test
    fun testLoadOutcomeReportsCorruptMagicDiscarded() {
        val key = "corrupt_magic_book"
        val cacheFile = File(cacheDir, "${ChapterDiskCache.hashKey(key)}.idx")
        DataOutputStream(BufferedOutputStream(FileOutputStream(cacheFile))).use { dos ->
            dos.writeInt(0x11223344)
            dos.writeInt(3)
        }

        val outcome = ChapterDiskCache.loadWithOutcome(cacheDir, key)

        assertNull(outcome.data)
        assertTrue("坏魔数必须报告为丢弃", outcome.discardedCorrupt)
        assertFalse("坏档应被删除以便重建", cacheFile.exists())
    }

    @Test
    fun testLoadOutcomeReportsShortFileDiscarded() {
        val key = "short_file_book"
        val cacheFile = File(cacheDir, "${ChapterDiskCache.hashKey(key)}.idx")
        cacheFile.writeBytes(byteArrayOf(0x57, 0x52, 0x43, 0x49)) // 只有魔数，不足 8 字节

        val outcome = ChapterDiskCache.loadWithOutcome(cacheDir, key)

        assertNull(outcome.data)
        assertTrue("半写入文件必须报告为丢弃", outcome.discardedCorrupt)
        assertFalse(cacheFile.exists())
    }

    @Test
    fun testLoadOutcomeReportsVersionMismatchDiscarded() {
        val key = "stale_version_book"
        val cacheFile = File(cacheDir, "${ChapterDiskCache.hashKey(key)}.idx")
        DataOutputStream(BufferedOutputStream(FileOutputStream(cacheFile))).use { dos ->
            dos.writeInt(0x57524349)
            dos.writeInt(2) // 旧协议版本，缺封顶续节
            dos.writeInt(500)
            dos.writeInt(1)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeUTF("旧版章节")
        }

        val outcome = ChapterDiskCache.loadWithOutcome(cacheDir, key)

        assertNull(outcome.data)
        assertTrue("协议版本过期必须报告为丢弃", outcome.discardedCorrupt)
        assertFalse(cacheFile.exists())
    }
}
