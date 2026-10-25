package com.freeturn.app.data.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

class InputTooLargeException(val limit: Int) : java.io.IOException("File exceeds $limit bytes")

fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - output.size() + 1).toInt())
        if (count < 0) return output.toByteArray()
        if (output.size().toLong() + count > limit) throw InputTooLargeException(limit)
        output.write(buffer, 0, count)
    }
}
