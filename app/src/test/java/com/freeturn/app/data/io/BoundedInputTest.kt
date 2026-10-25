package com.freeturn.app.data.io

import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test fun `accepts exactly the limit including empty files`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).inputStream().readBounded(3))
        assertEquals(0, byteArrayOf().inputStream().readBounded(0).size)
    }
    @Test fun `rejects a larger stream without reading the rest`() {
        var consumed = 0
        val endless = object : InputStream() { override fun read(): Int { consumed++; return 1 } }
        assertThrows(InputTooLargeException::class.java) { endless.readBounded(10) }
        assertEquals(11, consumed)
    }
    @Test fun `handles short reads across multiple chunks`() {
        val bytes = ByteArray(18000) { (it % 127).toByte() }
        val source = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(3, len))
        }
        assertArrayEquals(bytes, source.readBounded(bytes.size))
    }
    @Test fun `rejects even one byte at a zero limit`() {
        assertThrows(InputTooLargeException::class.java) { byteArrayOf(1).inputStream().readBounded(0) }
    }
}
