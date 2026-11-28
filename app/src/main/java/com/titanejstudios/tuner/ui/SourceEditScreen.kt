package com.titanejstudios.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanejstudios.tuner.Screen
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Source
import kotlinx.coroutines.delay

/** Add or edit a playlist (M3U or Xtream Codes). */
@Composable
fun SourceEditScreen(vm: TunerViewModel) {
    val existing = vm.editingSource
    val firstRun = vm.sources.isEmpty()
    var mode by remember { mutableIntStateOf(if (existing is Source.Xtream) 1 else 0) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var m3uUrl by remember { mutableStateOf((existing as? Source.M3u)?.playlistUrl ?: "") }
    var epgUrl by remember {
        mutableStateOf(
            when (existing) {
                is Source.M3u -> existing.epgUrl
                is Source.Xtream -> existing.epgUrl
                null -> null
            } ?: ""
        )
    }
    val xt = existing as? Source.Xtream
    var server by remember { mutableStateOf(xt?.server ?: "") }
    var username by remember { mutableStateOf(xt?.username ?: "") }
    var password by remember { mutableStateOf(xt?.password ?: "") }
    var useHls by remember { mutableStateOf(xt?.useHls ?: false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .skyBackground()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding(64.dp, 40.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (firstRun) "Welcome to Flicker" else if (existing == null) "Add a playlist" else "Edit playlist",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = TunerColors.TextPrimary,
        )
        Text(
            if (firstRun) "Already use Flicker on another TV or phone? Sign in with Google and your playlist comes across. " +
                "Otherwise, add your IPTV provider below."
            else "Changes are saved when you press Save.",
            fontSize = 18.sp,
            color = TunerColors.TextSecondary,
        )

        if (firstRun && vm.cloud.available) {
            // First: bring the playlist from your Google account (a QR code to scan on TVs).
            PillButton("🔑 Sign in with Google – bring your playlist across", { vm.navigate(Screen.Cloud) }, Modifier.focusRequester(firstFocus))
            Text("Or add one here:", fontSize = 16.sp, color = TunerColors.TextSecondary)
        }
        if (existing == null) {
            PillButton("📱 Set up from your phone instead", { vm.navigate(Screen.PhoneSetup) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("M3U playlist", { mode = 0 }, if (firstRun && vm.cloud.available) Modifier else Modifier.focusRequester(firstFocus), selected = mode == 0)
                PillButton("Xtream Codes login", { mode = 1 }, selected = mode == 1)
            }
        }

        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(
                "Name (e.g. \"Main provider\")", name, KeyboardType.Text,
                modifier = if (existing != null) Modifier.focusRequester(firstFocus) else Modifier,
            ) { name = it }
            if (mode == 0) {
                Field("Playlist URL (http://…/playlist.m3u)", m3uUrl, KeyboardType.Uri) { m3uUrl = it }
                Field("TV guide (XMLTV) URL – optional, uses the playlist's url-tvg if blank", epgUrl, KeyboardType.Uri) { epgUrl = it }
            } else {
                Field("Server (e.g. http://provider.com:8080)", server, KeyboardType.Uri) { server = it }
                Field("Username", username, KeyboardType.Text) { username = it }
                Field("Password", password, KeyboardType.Password, isPassword = true) { password = it }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Stream format: MPEG-TS", { useHls = false }, selected = !useHls)
                    PillButton("Stream format: HLS", { useHls = true }, selected = useHls)
                }
                Field("TV guide URL override – optional", epgUrl, KeyboardType.Uri) { epgUrl = it }
            }
        }

        problem?.let { Text(it, color = TunerColors.Error, fontSize = 16.sp) }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Save and load channels", {
                val epg = epgUrl.trim().ifEmpty { null }
                val id = existing?.id ?: vm.newSourceId()
                if (mode == 0) {
                    if (m3uUrl.isBlank()) {
                        problem = "Enter your playlist URL"
                    } else {
                        vm.saveSource(Source.M3u(id, name.trim().ifEmpty { "Playlist ${vm.sources.size + 1}" }, m3uUrl.trim(), epg))
                    }
                } else {
                    if (server.isBlank() || username.isBlank() || password.isBlank()) {
                        problem = "Enter the server, username and password"
                    } else {
                        vm.saveSource(
                            Source.Xtream(
                                id, name.trim().ifEmpty { "Provider ${vm.sources.size + 1}" },
                                server.trim(), username.trim(), password, useHls, epg,
                            )
                        )
                    }
                }
            })
            if (!firstRun) PillButton("Cancel", { vm.back() })
        }

        Text(
            "Tip: the Google TV app on your phone lets you type or paste into these boxes.",
            fontSize = 14.sp,
            color = TunerColors.TextSecondary,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth(),
    )
}
