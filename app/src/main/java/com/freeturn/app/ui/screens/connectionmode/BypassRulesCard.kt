package com.freeturn.app.ui.screens.connectionmode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.config.BypassRuleSet
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.viewmodel.settings.SettingsViewModel

@Composable
fun BypassRulesCard(config: ClientConfig, profileId: String?, locked: Boolean,
    settings: SettingsViewModel, edit: ((ClientConfig) -> ClientConfig) -> Unit) {
    var busy by remember(profileId) { mutableStateOf(false) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }
    val enabled = profileId != null && !locked && !busy
    // Preserve the target across the system file picker, even if the active profile changes.
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pickerTarget
        if (uri != null && id != null) {
            busy = true; error = null
            settings.importBypassRules(uri, id) { message -> busy = false; error = message }
        }
    }
    SettingsCard {
        SettingsSwitchRow(title = stringResource(R.string.bypass_rules_title),
            subtitle = stringResource(R.string.bypass_rules_desc),
            checked = config.bypassRulesEnabled, enabled = enabled && config.bypassRuleSets.isNotEmpty(),
            onCheckedChange = { value -> edit { it.copy(bypassRulesEnabled = value) } })
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bypass_rules_dns), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            config.bypassRuleSets.forEach { rule ->
                Row(Modifier.fillMaxWidth()) {
                    Text(rule.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = enabled, onClick = { edit { client ->
                        val next = client.bypassRuleSets.filterNot { it.name == rule.name }
                        client.copy(bypassRuleSets = next, bypassRulesEnabled = client.bypassRulesEnabled && next.isNotEmpty())
                    } }) { Text(stringResource(R.string.bypass_rules_remove)) }
                }
            }
            OutlinedButton(enabled = enabled, onClick = { pickerTarget = profileId; picker.launch("*/*") }) {
                Text(stringResource(R.string.bypass_rules_import))
            }
            OutlinedButton(enabled = enabled, onClick = {
                profileId?.let { id ->
                    busy = true; error = null
                    settings.importBypassRules(null, id) { message -> busy = false; error = message }
                }
            }) { Text(stringResource(R.string.bypass_rules_download)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (locked) Text(stringResource(R.string.bypass_rules_stop), style = MaterialTheme.typography.bodySmall)
        }
    }
}
