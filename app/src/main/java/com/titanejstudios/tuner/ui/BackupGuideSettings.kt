package com.titanejstudios.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.titanejstudios.backupguide.BackupGuide
import com.titanejstudios.tuner.TunerViewModel
import com.titanejstudios.tuner.data.Channel

/**
 * Backup guide (remove with the module) – personal use only. The settings for "Use extra guide
 * sources": the switch, the sources, and fixing a channel that was matched wrongly.
 */
@Composable
fun BackupGuideSettings(vm: TunerViewModel) {
    val ctx = LocalContext.current
    // Bumped after each change, so the rows show the new state.
    var version by remember { mutableIntStateOf(0) }
    val on = remember(version) { BackupGuide.enabled(ctx) }
    val custom = remember(version) { BackupGuide.customUrls(ctx) }
    val overrides = remember(version) { BackupGuide.overrides(ctx) }
    val remembered = remember(version) { BackupGuide.remembered(ctx) }

    var sourcesOpen by remember { mutableStateOf(false) }
    var addingUrl by remember { mutableStateOf(false) }
    var findingChannel by remember { mutableStateOf(false) }
    var channelResults by remember { mutableStateOf<List<Channel>?>(null) }
    var channel by remember { mutableStateOf<Channel?>(null) }
    var pickSource by remember { mutableStateOf<com.titanejstudios.backupguide.BackupGuideCore.Source?>(null) }
    var sourceResults by remember { mutableStateOf<List<com.titanejstudios.backupguide.BackupGuideCore.SourceChannel>?>(null) }

    fun changed() {
        version++
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingRow(
            "📅 Use extra guide sources",
            subtitle = "Personal use · fills channels with no listings, and gaps, from free guides for each country you have channels from (UK, US and US locals, Europe, Canada, Brazil, Arabic, Hong Kong…)",
            detail = if (on) "On" else "Off",
            onClick = {
                BackupGuide.setEnabled(ctx, !on)
                changed()
                if (!on) vm.updateGuideNow()
            },
        )
        if (on) {
            SettingRow(
                "Extra guide sources",
                subtitle = "The free guides used after your provider's, and any of your own",
                detail = "${BackupGuide.SOURCES.size + custom.size}",
                onClick = { sourcesOpen = true },
            )
            SettingRow(
                "Fix a guide match",
                subtitle = "A channel showing the wrong programmes? Choose its match by hand (or turn the extra guide off for it)",
                detail = if (overrides.isEmpty()) "${remembered.size} matched" else "${overrides.size} by hand",
                onClick = { findingChannel = true },
            )
        }
    }

    if (sourcesOpen) {
        MenuDialog(
            title = "Extra guide sources",
            subtitle = "Used in this order, after your provider's guide. Free guides from other people – they can change or stop.",
            items = BackupGuide.SOURCES.map { s -> MenuItem(s.name, s.about + (s.country?.let { " · only read if you have $it channels" } ?: "")) { } } +
                custom.map { url -> MenuItem("✓ " + url.substringAfter("://").take(60), "your own · OK to remove") {
                    BackupGuide.setCustomUrls(ctx, custom - url)
                    changed()
                } } +
                MenuItem("+ Add a guide address…", "an XMLTV link (.xml or .xml.gz)") { addingUrl = true } +
                MenuItem("⟳ Update the guide now", "in the background") { vm.updateGuideNow() },
            onDismiss = { sourcesOpen = false },
        )
    }
    if (addingUrl) {
        TextInputDialog(
            title = "Add a guide address",
            hint = "https://…/guide.xml.gz",
            label = "Address",
            onDismiss = { addingUrl = false },
            onDone = { url ->
                addingUrl = false
                val u = url.trim()
                if (u.startsWith("http", ignoreCase = true) && u !in custom) {
                    BackupGuide.setCustomUrls(ctx, custom + u)
                    changed()
                    vm.updateGuideNow()
                } else vm.showNotice("That doesn't look like a web address")
            },
        )
    }

    // Fixing a match: find the channel, then choose its source and the right channel in it.
    if (findingChannel) {
        TextInputDialog(
            title = "Which channel?",
            hint = "Part of its name, e.g. Sky Sports",
            label = "Channel",
            onDismiss = { findingChannel = false },
            onDone = { q ->
                findingChannel = false
                val t = q.trim().lowercase()
                channelResults = vm.allChannels.filter { t.isNotEmpty() && it.name.lowercase().contains(t) }.take(40)
            },
        )
    }
    channelResults?.let { list ->
        MenuDialog(
            title = if (list.isEmpty()) "No channel called that" else "Pick the channel",
            items = list.map { c ->
                MenuItem(c.name, describe(overrides[c.id] ?: remembered[c.id], overrides[c.id] != null)) {
                    channelResults = null
                    channel = c
                }
            },
            onDismiss = { channelResults = null },
        )
    }
    channel?.let { c ->
        if (pickSource == null && sourceResults == null) {
            MenuDialog(
                title = c.name,
                subtitle = "Extra guide: " + describe(overrides[c.id] ?: remembered[c.id], overrides[c.id] != null),
                items = listOf(
                    MenuItem("Automatic", "matched by guide ID, then by name") {
                        BackupGuide.setOverride(ctx, c.id, null)
                        changed()
                        channel = null
                        vm.showNotice("Back to automatic – used at the next guide update")
                    },
                    MenuItem("Don't use an extra guide for this channel") {
                        BackupGuide.setOverride(ctx, c.id, "off")
                        changed()
                        channel = null
                        vm.showNotice("No extra guide for ${c.name} from the next guide update")
                    },
                ) + BackupGuide.sources(ctx).map { s -> MenuItem("Choose from ${s.name}…", s.about) { pickSource = s } },
                onDismiss = { channel = null },
            )
        }
        pickSource?.let { s ->
            TextInputDialog(
                title = "Find it in ${s.name}",
                hint = "Part of the name as that guide has it",
                label = "Name",
                initial = c.name,
                onDismiss = { pickSource = null },
                onDone = { q ->
                    val t = BackupGuide.cleanName(q)
                    val all = BackupGuide.sourceChannels(ctx, s.key)
                    sourceResults = if (all.isEmpty()) emptyList() else all.filter { BackupGuide.cleanName(it.name).contains(t) || it.id.lowercase().contains(q.trim().lowercase()) }.take(40)
                },
            )
        }
        sourceResults?.let { list ->
            val s = pickSource
            MenuDialog(
                title = if (list.isEmpty()) "Nothing found" else "Which one is ${c.name}?",
                subtitle = if (list.isEmpty()) "That guide hasn't been read yet, or has no channel like that – turn the extra guide on and update first." else null,
                items = list.map { sc ->
                    MenuItem(sc.name, sc.id) {
                        if (s != null) {
                            BackupGuide.setOverride(ctx, c.id, s.key + "|" + sc.id)
                            changed()
                            vm.showNotice("${c.name} → ${sc.name} (${s.name}) – updating the guide")
                            vm.updateGuideNow()
                        }
                        sourceResults = null
                        pickSource = null
                        channel = null
                    }
                },
                onDismiss = {
                    sourceResults = null
                    pickSource = null
                },
            )
        }
    }
}

/** "From Freeview-EPG (bbc1.uk)", "Off for this channel", or "Not matched yet". */
private fun describe(value: String?, byHand: Boolean): String = when {
    value == null -> "Not matched yet"
    value == "off" -> "Off for this channel"
    else -> "From " + BackupGuide.sourceName(value.substringBefore('|')) + " (" + value.substringAfter('|') + ")" + if (byHand) " · set by hand" else ""
}
