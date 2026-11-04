package com.watchreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传书控制器契约：生命周期幂等、连接信息落 state、两条服务流镜像、失败折叠为未运行态。
 *
 * 全部用假 lan server 驱动，不碰 ServerSocket / Context / Robolectric：
 * 这就是把生命周期从 ReaderViewModel 剥出来的收益——旧实现只能靠起真服务或读代码来确认这些行为。
 */
class TransferControllerTest {

    private class FakeBinding(
        override val ipAddress: String?,
        override val port: Int,
        override val token: String?
    ) : LanTransferBinding {
        var stopCount = 0
        override fun stop() {
            stopCount++
        }
    }

    private class FakeServer : LanTransferServer {

        private val _progress = MutableStateFlow(TransferProgress())
        private val _uploadedCount = MutableStateFlow(0)

        override val progress: StateFlow<TransferProgress> = _progress
        override val uploadedCount: StateFlow<Int> = _uploadedCount

        var startCount = 0
        var failNextStart = false
        val bindings = mutableListOf<FakeBinding>()

        override fun start(): LanTransferBinding? {
            startCount++
            if (failNextStart) return null
            return FakeBinding("192.168.1.20", 8888 + startCount, "token-" + startCount)
                .also { bindings += it }
        }

        fun emitProgress(progress: TransferProgress) {
            _progress.value = progress
        }

        fun emitUploadedCount(count: Int) {
            _uploadedCount.value = count
        }
    }

    private class Harness {
        val server = FakeServer()
        var factoryCount = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = TransferController(scope) {
            factoryCount++
            server
        }
    }

    @Test
    fun `open 后 state 呈现运行中与本次绑定信息`() {
        val harness = Harness()
        harness.controller.open()

        val state = harness.controller.state.value
        assertTrue(state.isRunning)
        assertEquals("192.168.1.20", state.ipAddress)
        assertEquals(8889, state.port)
        assertEquals("token-1", state.token)
        assertEquals(1, harness.server.startCount)
    }

    @Test
    fun `open 幂等重复调用只启动一次且只建一个绑定`() {
        val harness = Harness()
        harness.controller.open()
        harness.controller.open()
        harness.controller.open()

        assertEquals(1, harness.server.startCount)
        assertEquals(1, harness.server.bindings.size)
        assertEquals(1, harness.factoryCount)
    }

    @Test
    fun `close 全量复位状态并只停止一次`() {
        val harness = Harness()
        harness.controller.open()
        harness.controller.close()

        assertEquals(TransferUiState(), harness.controller.state.value)
        assertEquals(1, harness.server.bindings.single().stopCount)

        harness.controller.close()
        assertEquals(1, harness.server.bindings.single().stopCount)
    }

    @Test
    fun `未 open 时 close 无副作用且不构造服务`() {
        val harness = Harness()
        harness.controller.close()

        assertEquals(TransferUiState(), harness.controller.state.value)
        assertEquals(0, harness.factoryCount)
        assertEquals(0, harness.server.startCount)
    }

    @Test
    fun `progress 三字段镜像进 state`() {
        val harness = Harness()
        harness.controller.open()
        harness.server.emitProgress(
            TransferProgress(
                isTransferring = true,
                fileName = "三体.txt",
                progress = 0.42f,
                bytesRead = 420L,
                totalBytes = 1000L
            )
        )

        val state = harness.controller.state.value
        assertTrue(state.isTransferring)
        assertEquals(0.42f, state.progress, 0.0001f)
        assertEquals("三体.txt", state.fileName)
        // 连接信息不被进度镜像覆盖
        assertTrue(state.isRunning)
        assertEquals("token-1", state.token)

        harness.server.emitProgress(TransferProgress())
        assertFalse(harness.controller.state.value.isTransferring)
        assertEquals(0f, harness.controller.state.value.progress, 0.0001f)
    }

    @Test
    fun `uploadedCount 订阅即回放当前值并随后续变化`() {
        val harness = Harness()
        harness.server.emitUploadedCount(5)

        harness.controller.open()
        assertEquals(5, harness.controller.state.value.uploadedCount)

        harness.server.emitUploadedCount(6)
        assertEquals(6, harness.controller.state.value.uploadedCount)
    }

    @Test
    fun `启动失败折叠为未运行态且不冒泡`() {
        val harness = Harness()
        harness.server.failNextStart = true

        harness.controller.open()

        val state = harness.controller.state.value
        assertFalse(state.isRunning)
        assertNull(state.token)
        assertNull(state.ipAddress)
        assertEquals(1, harness.server.startCount)

        // 未成功的运行没有绑定，close 不应去 stop 任何东西
        harness.controller.close()
        assertTrue(harness.server.bindings.isEmpty())
    }

    @Test
    fun `close 后到达的进度不再写回 state`() {
        val harness = Harness()
        harness.controller.open()
        harness.controller.close()

        harness.server.emitProgress(TransferProgress(isTransferring = true, progress = 0.9f, fileName = "迟到.txt"))

        assertEquals(TransferUiState(), harness.controller.state.value)
    }

    @Test
    fun `close 后可重新 open 并拿到新绑定`() {
        val harness = Harness()
        harness.controller.open()
        harness.controller.close()
        harness.controller.open()

        val state = harness.controller.state.value
        assertTrue(state.isRunning)
        assertEquals("token-2", state.token)
        assertEquals(8890, state.port)
        assertEquals(2, harness.server.startCount)
        // 服务实例跨 open 复用，工厂只调一次
        assertEquals(1, harness.factoryCount)
    }

    @Test
    fun `镜像协程在 runBlocking 作用域下同样收敛`() = runBlocking {
        val server = FakeServer()
        val controller = TransferController(this) { server }

        controller.open()
        settle()

        server.emitUploadedCount(2)
        server.emitProgress(TransferProgress(isTransferring = true, progress = 1f, fileName = "完.txt"))
        settle()

        assertEquals(2, controller.state.value.uploadedCount)
        assertEquals(1f, controller.state.value.progress, 0.0001f)

        controller.close()
        assertEquals(TransferUiState(), controller.state.value)
    }

    /** 让 runBlocking 事件循环把排队中的镜身子协程都跑完（订阅与回放都在这些协程里） */
    private suspend fun settle() {
        repeat(4) { yield() }
    }
}
