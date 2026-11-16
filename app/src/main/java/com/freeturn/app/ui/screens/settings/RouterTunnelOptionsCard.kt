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
    val setupBusy by vm.routerSetupBusy.collectAsStateWithLifecycle()
    val setupMessage by vm.routerSetupMessage.collectAsStateWithLifecycle()
    var setupExpanded by rememberSaveable { mutableStateOf(false) }
    var sshHost by rememberSaveable { mutableStateOf("192.168.1.1") }
    var sshPort by rememberSaveable { mutableStateOf("22") }
    var sshUser by rememberSaveable { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }
    var resetPhoneDialog by remember { mutableStateOf(false) }
    var resetRouterDialog by remember { mutableStateOf(false) }
    var automaticLan by remember(cfg) { mutableStateOf(cfg.automaticLan) }
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
        if (setupBusy) return
        if (automatic && pairingKey.isBlank()) pairingKey = RouterTunnelConfig.newPairingKey()
        val value = RouterTunnelConfig(enabled, listen.trim(), peer.trim(), port.toIntOrNull() ?: 0,
            peerPort.toIntOrNull() ?: 0, cidr.trim(), mtu.toIntOrNull() ?: 0, automatic, pairingKey.trim(), automaticLan)
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
            TextButton(onClick = { setupExpanded = !setupExpanded }, enabled = !setupBusy) {
                Text("Настроить подключение к роутеру")
            }
            if (setupExpanded) {
                Text("Подключите телефон к сети роутера. Нужен SSH с правами root и интернет на роутере для установки пакетов. ADB и отладка по USB не нужны. После настройки подключайте телефон как обычный USB-модем.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(sshHost, { sshHost = it }, label = { Text("Адрес роутера") }, singleLine = true, enabled = !setupBusy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, label = { Text("Порт SSH") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !setupBusy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sshUser, { sshUser = it }, label = { Text("Пользователь SSH") }, singleLine = true, enabled = !setupBusy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sshPassword, { sshPassword = it }, label = { Text("Пароль SSH") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !setupBusy, modifier = Modifier.fillMaxWidth())
                Text("Помощник установит необходимые пакеты и включит автозапуск. DNS роутера будет направлен через Fturn с сохранением прежней конфигурации для сброса. Пароль используется для текущей операции и не сохраняется.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    vm.setupRouter(sshHost, sshPort.toIntOrNull() ?: 0, sshUser, sshPassword)
                    sshPassword = ""
                }, enabled = !setupBusy && sshHost.isNotBlank() && sshUser.isNotBlank()) { Text("Настроить роутер") }
                TextButton(onClick = { resetRouterDialog = true }, enabled = !setupBusy) { Text("Сбросить настройки на роутере по SSH") }
            }
            if (setupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (setupMessage.isNotBlank()) Text(setupMessage, style = MaterialTheme.typography.bodySmall)

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
                if (automatic) SettingsSwitchRow(title = "Определять LAN автоматически", subtitle = "Принимать подсеть от привязанного роутера", checked = automaticLan, onCheckedChange = { automaticLan = it })
                if (!automatic || !automaticLan) OutlinedTextField(cidr, { cidr = it; message = "" }, label = { Text("Подсеть LAN роутера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); message = "" }, label = { Text("UDP-порт телефона") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(peerPort, { peerPort = it.filter(Char::isDigit).take(5); message = "" }, label = { Text("UDP-порт роутера") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mtu, { mtu = it.filter(Char::isDigit).take(4); message = "" }, label = { Text("MTU локального туннеля") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("MTU 1350 — стартовое значение для локального USB-туннеля. Оптимальное значение проверяется на подключённом роутере. MTU VPN-профиля на сервере настраивается отдельно.", style = MaterialTheme.typography.bodySmall)
                Text("FOU/IPIP передаёт IPv4 без шифрования и предназначен только для отдельной USB-сети с заданным роутером. После восстановления резервной копии режим выключается до проверки адресов.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { save(cfg.enabled) }, enabled = !setupBusy) { Text("Сохранить") }
            TextButton(onClick = { resetPhoneDialog = true }, enabled = !setupBusy) { Text("Сбросить IP-туннель в Fturn") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            Text(if (automatic) "При отключении телефона помощник роутера возвращает прежнюю схему HEV. После восстановления резервной копии режим нужно включить заново." else "После смены USB-адресов обновите оба адреса и переподключите VPN.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (resetPhoneDialog) AlertDialog(
        onDismissRequest = { resetPhoneDialog = false }, title = { Text("Сбросить IP-туннель?") },
        text = { Text("Раздача через IP-туннель отключится, код привязки будет удалён. Работающий VPN будет переподключён. Для новой привязки снова настройте роутер.") },
        confirmButton = { TextButton(onClick = { resetPhoneDialog = false; vm.resetRouterTunnel() }) { Text("Сбросить") } },
        dismissButton = { TextButton(onClick = { resetPhoneDialog = false }) { Text("Отмена") } })
    if (resetRouterDialog) AlertDialog(
        onDismissRequest = { resetRouterDialog = false }, title = { Text("Сбросить настройки роутера?") },
        text = { Text("Помощник Fturn остановится, его автозапуск отключится, созданные туннель и правила будут удалены. Сохранённые настройки DNS будут восстановлены, если их не меняли после установки. Введите пароль SSH перед сбросом.") },
        confirmButton = { TextButton(onClick = {
            resetRouterDialog = false
            vm.setupRouter(sshHost, sshPort.toIntOrNull() ?: 0, sshUser, sshPassword, reset = true)
            sshPassword = ""
        }) { Text("Сбросить на роутере") } },
        dismissButton = { TextButton(onClick = { resetRouterDialog = false }) { Text("Отмена") } })

}
