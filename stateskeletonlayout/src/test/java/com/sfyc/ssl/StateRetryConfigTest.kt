package com.sfyc.ssl

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * StateRetryConfig 契约测试：不可变性、去重、三态语义、无效值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateRetryConfigTest {

    @Test
    fun `输入可变列表修改不影响快照`() {
        val mutable = mutableListOf(1, 2, 2, 3)
        val config = StateRetryConfig(emptyViewIds = mutable, errorViewIds = null)
        mutable.add(99)
        mutable[0] = 100
        assertEquals(listOf(1, 2, 3), config.emptyViewIds)
    }

    @Test
    fun `返回值不可修改`() {
        val config = StateRetryConfig(emptyViewIds = listOf(1, 2), errorViewIds = listOf(3))
        val empty = config.emptyViewIds!!
        try {
            (empty as java.util.List<Int>).add(99)
            throw AssertionError("返回值应不可修改")
        } catch (e: UnsupportedOperationException) {
            // 期望：不可修改快照。
        }
        assertEquals(listOf(1, 2), config.emptyViewIds)
    }

    @Test
    fun `返回每次为独立快照`() {
        val config = StateRetryConfig(emptyViewIds = listOf(1), errorViewIds = null)
        val first = config.emptyViewIds!!
        val second = config.emptyViewIds!!
        assertEquals(first, second)
        assertNotSame(first, second)
    }

    @Test
    fun `稳定去重保持首次出现顺序`() {
        val config = StateRetryConfig(emptyViewIds = listOf(3, 1, 3, 2, 1), errorViewIds = null)
        assertEquals(listOf(3, 1, 2), config.emptyViewIds)
    }

    @Test
    fun `null跳过空列表关闭`() {
        val emptyOp = StateRetryConfig(emptyViewIds = null, errorViewIds = null)
        assertNull(emptyOp.emptyViewIds)
        assertNull(emptyOp.errorViewIds)

        val close = StateRetryConfig(emptyViewIds = emptyList(), errorViewIds = listOf(1))
        assertTrue(close.emptyViewIds!!.isEmpty())
        assertEquals(listOf(1), close.errorViewIds)
    }

    @Test
    fun `零和NO_ID拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            StateRetryConfig(emptyViewIds = listOf(0), errorViewIds = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StateRetryConfig(emptyViewIds = null, errorViewIds = listOf(View.NO_ID))
        }
    }

    @Test
    fun `超限拒绝`() {
        val huge = (1..(StateRetryConfig.MAX_RETRY_IDS + 1)).toList()
        val failure = runCatching { StateRetryConfig(emptyViewIds = huge, errorViewIds = null) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("超限"))
    }

    @Test
    fun `相同ID可同时出现在EMPTY和ERROR`() {
        val config = StateRetryConfig(emptyViewIds = listOf(1, 2), errorViewIds = listOf(2, 3))
        assertEquals(listOf(1, 2), config.emptyViewIds)
        assertEquals(listOf(2, 3), config.errorViewIds)
    }

    @Test
    fun `结构化相等与toString`() {
        val a = StateRetryConfig(emptyViewIds = listOf(1, 1, 2), errorViewIds = null)
        val b = StateRetryConfig(emptyViewIds = listOf(1, 2), errorViewIds = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("emptyViewIds"))
    }
}
