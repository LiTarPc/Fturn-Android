import kotlin.math.*
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val k = 1024.0
    val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (ln(bytes.toDouble()) / ln(k)).toInt().coerceIn(0, sizes.size - 1)
    val value = bytes / k.pow(i.toDouble())
    return "%.2f %s".format(value, sizes[i])
}
println("100: " + formatBytes(100L))
println("1024: " + formatBytes(1024L))
println("2048: " + formatBytes(2048L))
println("1048576: " + formatBytes(1048576L))
println("1048575: " + formatBytes(1048575L))
