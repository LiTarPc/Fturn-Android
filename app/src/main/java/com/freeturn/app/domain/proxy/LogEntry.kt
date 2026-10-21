package com.freeturn.app.domain.proxy

enum class LogLevel { Plain, Event, Success, Warning, Error }

data class LogEntry(val id: Long, val text: String, val level: LogLevel)

private val successfulLogMessage = Regex(
    """\b(?:success(?:ful(?:ly)?)?|started|established|connected)\b|(?<![\p{L}\p{N}_])(?:запущен[аоы]?|подключ[её]н[аоы]?)(?![\p{L}\p{N}_])|\bturn allocation up\b|\braw stream \d+ ready:"""
)
private val inactiveLogMessage = Regex(
    """\b(?:not|never) (?:connected|started|established|successful)\b|\bdisconnected\b|(?<![\p{L}\p{N}_])не (?:подключ[её]н|запущен)[аоы]?(?![\p{L}\p{N}_])"""
)

/** Уровень строки по ключевым словам ядра и наших сообщений (ru + en). */
fun classifyLogLine(line: String): LogLevel {
    val lower = line.lowercase()
    return when {
        lower.contains("ошибка") || lower.contains("error") ||
            lower.contains("критическая") || lower.contains("failed") ||
            lower.contains("fatal") || lower.contains("panic") ||
            lower.contains("не удалось") -> LogLevel.Error

        lower.contains("watchdog") || lower.contains("перезапуск") ||
            lower.contains("переподключение") || lower.contains("квота") ||
            lower.contains("quota") || lower.contains("warn") ||
            lower.contains("недоступна") -> LogLevel.Warning

        inactiveLogMessage.containsMatchIn(lower) -> LogLevel.Event
        successfulLogMessage.containsMatchIn(lower) -> LogLevel.Success

        lower.contains("запуск прокси") || lower.contains("остановка") ||
            lower.contains("процесс остановлен") || lower.contains("сессия завершена") ||
            lower.contains("быстрый выход") || lower.startsWith("сеть:") -> LogLevel.Event

        else -> LogLevel.Plain
    }
}
