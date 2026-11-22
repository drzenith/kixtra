package com.drzenith.kixtra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(actions: Actions) {
    val store = actions.store
    var bg by remember { mutableStateOf(store.backgroundAudio) }
    var pip by remember { mutableStateOf(store.pipEnabled) }
    var chat by remember { mutableStateOf(store.chatEnabled) }
    var rewind by remember { mutableStateOf(store.rewindableLive) }
    var mature by remember { mutableStateOf(store.hideMature) }
    var language by remember { mutableStateOf(store.defaultLanguage) }
    var langOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Languages.ensureLoaded() }

    Column(Modifier.fillMaxSize()) {
        BackBar("Settings", onBack = { actions.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Browsing")
            Box {
                Item("Default language", languageName(language)) { langOpen = true }
                DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                    (listOf("") + Languages.codes).forEach { code ->
                        DropdownMenuItem(
                            text = { Text(languageName(code), fontWeight = if (code == language) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                langOpen = false
                                language = code
                                actions.setDefaultLanguage(code)
                            },
                        )
                    }
                }
            }

            Section("Playback")
            Toggle(
                "Rewindable live streams",
                "Live streams play their VOD at the live point, so you can scroll back anytime. Turn off for the plain live stream (a few seconds less delay, no rewinding).",
                rewind,
            ) { rewind = it; store.rewindableLive = it }
            Toggle("Picture-in-picture", "Press Home while watching to keep the video in a small window", pip) {
                pip = it; store.pipEnabled = it
            }
            Toggle(
                "Background audio",
                "Keep listening when you leave the app or close the picture-in-picture window (video stops downloading)",
                bg,
            ) { bg = it; store.backgroundAudio = it }
            Toggle("Chat", "Live chat and VOD chat replay under the player", chat) {
                chat = it; store.chatEnabled = it; actions.player.syncChat()
            }

            Section("Content")
            Toggle("Hide 18+ streams", "Hides streams marked mature in Live and category lists", mature) {
                mature = it; store.hideMature = it
            }
            Text(
                "Kixtra uses Kick's unofficial API; if something stops loading, Kick probably changed it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = AccentViolet,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
