@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens.home

import android.net.TrafficStats
import android.os.Process
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.freeturn.app.ui.theme.HeroSquircleShape
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.ui.theme.extendedColorScheme
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Герой главного экрана: PWDTT сквиркл-кнопка, строка статуса и карточка статистики. */
@Composable
internal fun ConnectionHero(
    state: ProxyState,
    uptimeText: String?,
    tunnelActive: Boolean,
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

        StatusLabel(state = state, tunnelActive = tunnelActive)

        Spacer(Modifier.height(18.dp))

        TrafficStatsCard(
            isActive = kind == HeroKind.Running
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
    val extended = MaterialTheme.extendedColorScheme
    val buttonLabel = when (kind) {
        HeroKind.Busy -> stringResource(R.string.proxy_connecting)
        HeroKind.Running -> stringResource(
            if (tunnelActive) R.string.tunnel_active_stop else R.string.proxy_active_stop
        )
        HeroKind.Error -> stringResource(R.string.proxy_error_restart)
        HeroKind.Idle -> stringResource(R.string.start_proxy)
    }

    val colorSpec = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    val containerColor by animateColorAsState(
        targetValue = when (kind) {
            HeroKind.Running -> extended.successContainer // Solid black in light, light gray in dark
            HeroKind.Error -> MaterialTheme.colorScheme.errorContainer
            HeroKind.Busy -> MaterialTheme.colorScheme.surfaceContainerHigh
            HeroKind.Idle -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = colorSpec,
        label = "btn_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when (kind) {
            HeroKind.Running -> extended.onSuccessContainer // White in light, black in dark
            HeroKind.Error -> MaterialTheme.colorScheme.onErrorContainer
            HeroKind.Busy -> MaterialTheme.colorScheme.onSurfaceVariant
            HeroKind.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = colorSpec,
        label = "btn_fg"
    )

    val borderColor by animateColorAsState(
        targetValue = when (kind) {
            HeroKind.Running -> containerColor
            HeroKind.Error -> MaterialTheme.colorScheme.error
            HeroKind.Busy -> MaterialTheme.colorScheme.outline
            HeroKind.Idle -> MaterialTheme.colorScheme.outline
        },
        animationSpec = colorSpec,
        label = "btn_border"
    )

    val rotation = rememberHeroSpin(spinning = kind == HeroKind.Busy && !reducedMotion)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(140.dp)
            .border(1.dp, borderColor, HeroSquircleShape)
            .semantics { contentDescription = buttonLabel },
        shape = HeroSquircleShape,
        color = containerColor,
        tonalElevation = if (kind == HeroKind.Running) 4.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.power_24px),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        if (kind == HeroKind.Busy && !reducedMotion) {
                            rotationZ = rotation.value
                        }
                    },
                tint = contentColor
            )
        }
    }
}

@Composable
private fun rememberHeroSpin(spinning: Boolean): State<Float> {
    val angle = remember { Animatable(0f) }
    val settleSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(spinning) {
        if (spinning) {
            angle.animateTo(angle.value + 360f * 240, tween(240 * 1_500, easing = LinearEasing))
        } else if (angle.value != 0f) {
            angle.animateTo(ceil(angle.value / 360f) * 360f, settleSpec)
            angle.snapTo(0f)
        }
    }
    return angle.asState()
}

@Composable
private fun StatusLabel(state: ProxyState, tunnelActive: Boolean) {
    val label = when (state) {
        is ProxyState.Running -> "Отключить"
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
    isActive: Boolean
) {
    var rxBytes by remember { mutableLongStateOf(0L) }
    var txBytes by remember { mutableLongStateOf(0L) }
    var downSpeed by remember { mutableLongStateOf(0L) }
    var upSpeed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            rxBytes = 0L
            txBytes = 0L
            downSpeed = 0L
            upSpeed = 0L
            return@LaunchedEffect
        }

        val myUid = Process.myUid()
        var prevRx = TrafficStats.getUidRxBytes(myUid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        var prevTx = TrafficStats.getUidTxBytes(myUid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        val initialRx = prevRx
        val initialTx = prevTx

        while (true) {
            delay(1000L)
            val currentRx = TrafficStats.getUidRxBytes(myUid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
            val currentTx = TrafficStats.getUidTxBytes(myUid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L

            downSpeed = max(0L, currentRx - prevRx)
            upSpeed = max(0L, currentTx - prevTx)
            rxBytes = max(0L, currentRx - initialRx)
            txBytes = max(0L, currentTx - initialTx)

            prevRx = currentRx
            prevTx = currentTx
        }
    }

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
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
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
                            text = "${formatSpeed(downSpeed)} ↓",
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
                            text = formatBytes(rxBytes),
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
                            text = "${formatSpeed(upSpeed)} ↑",
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
                            text = formatBytes(txBytes),
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
    val k = 1024.0
    val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (ln(bytes.toDouble()) / ln(k)).toInt().coerceIn(0, sizes.size - 1)
    val value = bytes / k.pow(i.toDouble())
    return "%.2f %s".format(value, sizes[i])
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val k = 1024.0
    val sizes = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    val i = (ln(bytesPerSec.toDouble()) / ln(k)).toInt().coerceIn(0, sizes.size - 1)
    val value = bytesPerSec / k.pow(i.toDouble())
    return "%.1f %s".format(value, sizes[i])
}

