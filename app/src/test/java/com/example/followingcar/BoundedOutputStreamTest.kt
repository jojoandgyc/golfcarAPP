package com.example.followingcar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutputStreamTest {
    @Test
    fun excessiveOutputIsAcceptedWithoutGrowingTheRetainedBuffer() {
        val output = BoundedOutputStream(8)
        output.write("prefix".toByteArray())
        repeat(100) { output.write(ByteArray(4096) { 'x'.code.toByte() }) }
        assertEquals("prefixxx", output.text())
        assertTrue(output.truncated)
    }

    @Test
    fun exactLimitDoesNotClaimThatOutputWasTruncated() {
        val output = BoundedOutputStream(4)
        output.write("1234".toByteArray())
        output.write(byteArrayOf(), 0, 0)
        assertEquals("1234", output.text())
        assertFalse(output.truncated)
        output.write('5'.code)
        assertTrue(output.truncated)
        assertEquals("1234", output.text())
    }

    @Test
    fun fragmentedUtf8IsDecodedAfterAllChunksArrive() {
        val output = BoundedOutputStream(64)
        val encoded = "开发板已连接".toByteArray(Charsets.UTF_8)
        encoded.forEach { output.write(it.toInt()) }
        assertEquals("开发板已连接", output.text())
        assertFalse(output.truncated)
    }

    @Test
    fun writeHonorsTheRequestedByteRange() {
        val output = BoundedOutputStream(3)
        output.write("xxabcdyy".toByteArray(), 2, 4)
        assertEquals("abc", output.text())
        assertTrue(output.truncated)
    }

    @Test
    fun connectionDescriptionNeverIncludesThePassword() {
        val config = BoardConnection("172.16.16.82", 22, "topeet", "secret-password")
        assertFalse(config.toString().contains("secret-password"))
    }
}
