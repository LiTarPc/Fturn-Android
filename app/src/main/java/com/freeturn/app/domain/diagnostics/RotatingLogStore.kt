package com.freeturn.app.domain.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Called by one IO worker; synchronized exports also provide a consistent snapshot. */
class RotatingLogStore(
    private val directory: File,
    private val capacityBytes: Long = 50L * 1024 * 1024,
    private val segmentBytes: Long = 5L * 1024 * 1024,
) : Closeable {
    private val segmentCount: Int
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private var output: FileOutputStream? = null
    private var currentBytes = 0L
    private var bytes = 0L

    init {
        require(segmentBytes >= 128 && capacityBytes % segmentBytes == 0L)
        require(capacityBytes / segmentBytes in 1..100)
        segmentCount = (capacityBytes / segmentBytes).toInt()
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic directory" }
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("events-[0-9]{2}\\.log")) }
            .filter { it.name.substring(7, 9).toInt() >= segmentCount || it.length() > segmentBytes }
            .forEach(::remove)
        bytes = files().sumOf { it.length() }
        currentBytes = file(0).length()
    }

    private fun file(index: Int) = File(directory, "events-%02d.log".format(Locale.US, index))
    private fun files() = (segmentCount - 1 downTo 0).map(::file).filter { it.isFile }
    private fun remove(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove diagnostic segment")
    }

    @Synchronized
    fun sizeBytes(): Long = bytes

    @Synchronized
    fun append(message: String, timeMillis: Long = System.currentTimeMillis()) {
        val text = "${timestamp.format(Date(timeMillis))} $message\n".toByteArray(Charsets.UTF_8)
        val limit = minOf(segmentBytes, 64L * 1024).toInt()
        val record = if (text.size <= limit) text else {
            val suffix = " [truncated]\n".toByteArray()
            var end = limit - suffix.size
            // Do not cut a UTF-8 character in the middle.
            while (end > 0 && (text[end].toInt() and 0xc0) == 0x80) end--
            text.copyOf(end) + suffix
        }
        // Reconcile a partial write after a storage error before applying the cap.
        val actual = file(0).length()
        bytes += actual - currentBytes
        currentBytes = actual
        if (currentBytes + record.size > segmentBytes) rotate()
        val stream = output ?: FileOutputStream(file(0), true).also { output = it }
        // Unbuffered: completed writes survive application process termination.
        stream.write(record)
        currentBytes += record.size
        bytes += record.size
    }

    private fun rotate() {
        output?.close()
        output = null
        val oldest = file(segmentCount - 1)
        val removed = oldest.length()
        remove(oldest)
        bytes -= removed
        for (index in segmentCount - 2 downTo 0) {
            val old = file(index)
            if (old.exists() && !old.renameTo(file(index + 1))) {
                throw IOException("Cannot rotate diagnostic segment")
            }
        }
        currentBytes = 0
    }

    @Synchronized
    fun exportArchive(target: File, metadata: String): File {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val partial = File(target.parentFile, target.name + ".part")
        try {
            output?.flush()
            ZipOutputStream(FileOutputStream(partial)).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostic-info.txt"))
                zip.write(metadata.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("events.log"))
                files().forEach { file -> file.inputStream().use { it.copyTo(zip) } }
                zip.closeEntry()
            }
            remove(target)
            if (!partial.renameTo(target)) throw IOException("Cannot finish diagnostic export")
            return target
        } finally {
            remove(partial)
        }
    }

    @Synchronized
    fun clear() {
        output?.close()
        output = null
        files().forEach(::remove)
        currentBytes = 0
        bytes = 0
    }

    @Synchronized
    override fun close() {
        output?.close()
        output = null
    }
}
