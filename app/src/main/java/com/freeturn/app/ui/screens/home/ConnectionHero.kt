@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.CircularProgressIndicator
import com.freeturn.app.ui.theme.WinePalette
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freeturn.app.R
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.domain.TrafficSnapshot
import com.freeturn.app.ui.theme.HeroSquircleShape
import com.freeturn.app.ui.theme.LocalReducedMotion
import java.util.Locale

/** Герой главного экрана: PWDTT сквиркл-кнопка, строка статуса и карточка статистики. */
@Composable
internal fun ConnectionHero(
    state: ProxyState,
    uptimeText: String?,
    tunnelActive: Boolean,
    trafficSnapshot: TrafficSnapshot,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val kind = state.heroKind()
    val reducedMotion = LocalReducedMotion.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroToggleButton(
            kind = kind,
            tunnelActive = tunnelActive,
            reducedMotion = reducedMotion,
            onClick = onToggle
        )

        Spacer(Modifier.height(18.dp))

        StatusLabel(
            state = state,
            uptimeText = uptimeText,
            tunnelActive = tunnelActive
        )

        Spacer(Modifier.height(18.dp))

        TrafficStatsCard(
            isActive = kind == HeroKind.Running,
            trafficSnapshot = trafficSnapshot
        )
    }
}

private enum class HeroKind { Idle, Busy, Running, Error }

private fun ProxyState.heroKind(): HeroKind = when (this) {
    is ProxyState.Running -> HeroKind.Running
    is ProxyState.Starting, is ProxyState.Connecting,
    is ProxyState.CaptchaRequired -> HeroKind.Busy
    is ProxyState.Error -> HeroKind.Error
    is ProxyState.Idle -> HeroKind.Idle
}

@Composable
private fun HeroToggleButton(
    kind: HeroKind,
    tunnelActive: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit
) {
    val buttonLabel = when (kind) {
        HeroKind.Busy -> stringResource(R.string.proxy_connecting)
        HeroKind.Running -> stringResource(
            if (tunnelActive) R.string.tunnel_active_stop else R.string.proxy_active_stop
        )
        HeroKind.Error -> stringResource(R.string.proxy_error_restart)
        HeroKind.Idle -> stringResource(R.string.start_proxy)
    }

    val colorSpec = if (reducedMotion) androidx.compose.animation.core.snap<Color>()
        else MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()

    val containerColor by animateColorAsState(
        targetValue = when {
            pressed -> WinePalette.Pressed
            hovered || focused -> WinePalette.Highlight
            kind == HeroKind.Error -> WinePalette.Highlight
            kind == HeroKind.Busy -> WinePalette.Highlight
            else -> WinePalette.Primary
        },
        animationSpec = colorSpec,
        label = "btn_bg"
    )

    val contentColor = WinePalette.Cream

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(140.dp)
            .semantics { contentDescription = buttonLabel },
        shape = HeroSquircleShape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactions,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (kind == HeroKind.Busy && !reducedMotion) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                    trackColor = contentColor.copy(alpha = 0.18f)
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.power_24px),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = contentColor
                )
            }
        }
    }
}

@Composable
private fun StatusLabel(
    state: ProxyState,
    uptimeText: String?,
    tunnelActive: Boolean
) {
    val label = when (state) {
        is ProxyState.Running -> {
            val base = if (tunnelActive) "Туннель активен" else "Подключено"
            if (!uptimeText.isNullOrBlank()) "$base • $uptimeText" else base
        }
        is ProxyState.Starting, is ProxyState.Connecting -> "Подключение..."
        is ProxyState.Error -> state.message
        is ProxyState.CaptchaRequired -> "Требуется капча"
        else -> "Подключить"
    }

    val color = when (state) {
        is ProxyState.Running -> MaterialTheme.colorScheme.onSurfaceVariant
        is ProxyState.Error, is ProxyState.CaptchaRequired -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp
        ),
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp)
    )
}

@Composable
private fun TrafficStatsCard(
    isActive: Boolean,
    trafficSnapshot: TrafficSnapshot
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f)
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.9f)
                    .height(76.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Скачано
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${formatSpeed(trafficSnapshot.downSpeed)} ↓",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "СКАЧАНО",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = formatBytes(trafficSnapshot.rxBytes),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline)
                    )

                    // Отправлено
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${formatSpeed(trafficSnapshot.upSpeed)} ↑",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "ОТПРАВЛЕНО",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = formatBytes(trafficSnapshot.txBytes),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
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

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}


