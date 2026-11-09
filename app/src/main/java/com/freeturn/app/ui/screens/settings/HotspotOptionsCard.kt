package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.config.HotspotConfig
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel

@Composable
fun HotspotOptionsCard(vm: SettingsViewModel) {
    val enabled by vm.hotspotProxyEnabled.collectAsStateWithLifecycle()
    val loaded by produceState<HotspotConfig?>(null) { value = vm.hotspotConfig() }
    val cfg = loaded ?: return
    var port by remember(cfg.port) { mutableStateOf(cfg.port.toString()) }
    var password by remember(cfg.password) { mutableStateOf(cfg.password) }
    var saved by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
    val valid = port.toIntOrNull() in 1024..65535 && password.toByteArray(Charsets.UTF_8).size in 8..255
    SettingsCard {
        SettingsSwitchRow(title = "Раздача VPN", subtitle = "Другим устройствам через SOCKS5-прокси",
            checked = enabled, onCheckedChange = { vm.setHotspotProxyEnabled(it) })
        if (enabled) Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Включите точку доступа Android. На другом устройстве укажите SOCKS5: IP телефона в сети раздачи и порт ниже. TCP и UDP; для UDP нужна поддержка SOCKS5 UDP на клиенте.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5); saved = false },
                label = { Text("Порт SOCKS5") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Логин: ${HotspotConfig.USER}")
            OutlinedTextField(value = password, onValueChange = { password = it; saved = false }, label = { Text("Пароль SOCKS5 (8–255 байт)") },
                visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { revealPassword = !revealPassword }) { Text(if (revealPassword) "Скрыть" else "Показать") } }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = valid, onClick = { vm.saveHotspotConfig(port.toInt(), password); saved = true }) { Text("Сохранить") }
            if (saved) Text("Параметры сохранены.", color = MaterialTheme.colorScheme.primary)
        }
        Text("Изменения вступят в силу при следующем подключении WG, VLESS или RAW. Без VPN прокси-порт закрыт.", modifier = Modifier.padding(Spacing.lg), style = MaterialTheme.typography.bodySmall)
    }
}
