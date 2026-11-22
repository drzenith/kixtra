package com.drzenith.kixtra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(actions: Actions) {
    val store = actions.store
    var bg by remember { mutableStateOf(store.backgroundAudio) }
    var pip by remember { mutableStateOf(store.pipEnabled) }
    var chat by remember { mutableStateOf(store.chatEnabled) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.back() }) { Text("←") }
            Text("Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Toggle("Background audio", "Keep listening when you leave the app (video stops downloading)", bg) {
            bg = it; store.backgroundAudio = it
        }
        Toggle("Picture-in-picture", "Shrink the player when you press Home while watching", pip) {
            pip = it; store.pipEnabled = it
        }
        Toggle("Chat", "Show live chat and VOD chat replay under the player", chat) {
            chat = it; store.chatEnabled = it
        }
        Text(
            "Quality and VOD speed are remembered from the last ones you picked in the player.\n\n" +
                "Kixtra uses Kick's unofficial API; if something stops loading, Kick probably changed it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
