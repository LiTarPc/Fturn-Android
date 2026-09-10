import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.2f %s", value, units[i])
}

println("0: " + formatBytes(0L))
println("100: " + formatBytes(100L))
println("1024: " + formatBytes(1024L))
println("2048: " + formatBytes(2048L))
println("1048575: " + formatBytes(1048575L))
println("1048576: " + formatBytes(1048576L))
println("1073741824: " + formatBytes(1073741824L))

