package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.config.RouterTunnelConfig
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun RouterTunnelOptionsCard(vm: SettingsViewModel) {
    val cfg by vm.routerTunnel.collectAsStateWithLifecycle()
    var listen by remember(cfg) { mutableStateOf(cfg.listen) }
    var peer by remember(cfg) { mutableStateOf(cfg.peer) }
    var port by remember(cfg) { mutableStateOf(cfg.port.toString()) }
    var peerPort by remember(cfg) { mutableStateOf(cfg.peerPort.toString()) }
    var cidr by remember(cfg) { mutableStateOf(cfg.sourceCidr) }
    var mtu by remember(cfg) { mutableStateOf(cfg.mtu.toString()) }
    var automatic by remember(cfg) { mutableStateOf(if (cfg.listen.isBlank() && cfg.peer.isBlank()) true else cfg.automatic) }
    var pairingKey by remember(cfg) { mutableStateOf(cfg.pairingKey) }
    var showKey by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    fun save(enabled: Boolean) {
        if (automatic && pairingKey.isBlank()) pairingKey = RouterTunnelConfig.newPairingKey()
        val value = RouterTunnelConfig(enabled, listen.trim(), peer.trim(), port.toIntOrNull() ?: 0,
            peerPort.toIntOrNull() ?: 0, cidr.trim(), mtu.toIntOrNull() ?: 0, automatic, pairingKey.trim())
        val error = runCatching { value.validate() }.exceptionOrNull()
        if (enabled && error != null) { message = error.message.orEmpty(); return }
        scope.launch {
            runCatching { vm.saveRouterTunnel(value) }
                .onSuccess { message = "Сохранено. Переподключите VPN для применения." }
                .onFailure { message = "Не удалось сохранить настройки туннеля" }
        }
    }
    SettingsCard {
        SettingsSwitchRow(title = "IP-туннель роутера",
            subtitle = "Экспериментальный режим через USB",
            checked = cfg.enabled, onCheckedChange = { save(it) })
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SettingsSwitchRow(title = "Автоматическая привязка", subtitle = "Подключение без ввода IP-адресов", checked = automatic, onCheckedChange = { automatic = it; message = "" })
            if (automatic) {
                Text("Один раз укажите одинаковый код в Fturn и на роутере с помощником Fturn. Затем подключите USB-модем и запустите VPN: адреса и туннель обновятся автоматически.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pairingKey, { pairingKey = it.trim(); message = "" }, label = { Text("Код привязки роутера") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Скрыть код" else "Показать код") }
                    TextButton(onClick = { pairingKey = RouterTunnelConfig.newPairingKey(); message = "Новый код нужно также указать на роутере." }) { Text("Создать") }
                    TextButton(onClick = { if (pairingKey.isBlank()) pairingKey = RouterTunnelConfig.newPairingKey(); clipboard.setText(AnnotatedString(pairingKey)); message = "Код скопирован. Сохраните настройки." }) { Text("Копировать") }
                }
                Text("Если код пуст, при сохранении будет создан новый. USB-модем включается в настройках Android.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Введите адреса телефона и роутера в USB-сети. Изменения применяются после переподключения VPN.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(listen, { listen = it; message = "" }, label = { Text("IPv4 телефона в USB-сети") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(peer, { peer = it; message = "" }, label = { Text("IPv4 роутера в USB-сети") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Скрыть дополнительные параметры" else "Дополнительно")
            }
            if (advanced) {
                OutlinedTextField(cidr, { cidr = it; message = "" }, label = { Text("Подсеть LAN роутера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); message = "" }, label = { Text("UDP-порт телефона") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(peerPort, { peerPort = it.filter(Char::isDigit).take(5); message = "" }, label = { Text("UDP-порт роутера") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mtu, { mtu = it.filter(Char::isDigit).take(4); message = "" }, label = { Text("MTU локального туннеля") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("MTU 1350 — стартовое значение для локального USB-туннеля. Оптимальное значение проверяется на подключённом роутере. MTU VPN-профиля на сервере настраивается отдельно.", style = MaterialTheme.typography.bodySmall)
                Text("FOU/IPIP передаёт IPv4 без шифрования и предназначен только для отдельной USB-сети с заданным роутером. После восстановления резервной копии режим выключается до проверки адресов.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { save(cfg.enabled) }) { Text("Сохранить") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            Text(if (automatic) "При отключении телефона помощник роутера возвращает прежнюю схему HEV. После восстановления резервной копии режим нужно включить заново." else "После смены USB-адресов обновите оба адреса и переподключите VPN.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
