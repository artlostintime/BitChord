package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.extensions.ExtensionRegistryClient
import com.music.bitchord.data.extensions.SflxInstaller
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SongRowSkeleton
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

/**
 * The extension store: a registry of installable SpotiFLAC (.sflx) extensions
 * fetched from the configured repo URLs, plus the repo list itself.
 *
 * Install/uninstall go through [SflxInstaller]; "already installed" is read
 * from the installer's own record of what's on disk, which is the source of
 * truth — a [com.music.bitchord.data.sources.SourceRegistry] config only
 * exists because an install created it.
 */
@Composable
fun ExtensionsScreen(
    contentPadding: PaddingValues,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repoUrls by AppSettings.extensionRepoUrls.collectAsStateWithLifecycle()

    var entries by remember { mutableStateOf<List<ExtensionRegistryClient.RegistryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    /** Installed ids, refreshed after each install/uninstall so the row flips. */
    var installedIds by remember { mutableStateOf(SflxInstaller.installed(context).keys) }
    val installing = remember { mutableStateMapOf<String, Boolean>() }
    val errors = remember { mutableStateMapOf<String, String?>() }

    fun refreshInstalled() {
        installedIds = SflxInstaller.installed(context).keys
    }

    // Re-fetch whenever the repo list changes — adding a URL should pull it in.
    // ponytail: no debounce; repo edits are deliberate, not keystrokes.
    LaunchedEffect(repoUrls) {
        loading = true
        loadError = null
        val result = runCatching { ExtensionRegistryClient.fetch(repoUrls) }
        result.onSuccess { entries = it }
            .onFailure { loadError = it.message ?: "Failed to load extensions" }
        loading = false
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
    ) {
        item {
            Column(Modifier.padding(horizontal = GROUP_INSET, vertical = 8.dp)) {
                Text(
                    text = "Extension Store",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Browse and install SpotiFLAC sources.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            when {
                loading -> {
                    FrostedGroup(hazeState = hazeState) {
                        repeat(4) { SongRowSkeleton(index = it) }
                    }
                }
                loadError != null -> {
                    FrostedGroup(hazeState = hazeState) {
                        MessageState(message = loadError!!)
                    }
                }
                entries.isEmpty() -> {
                    FrostedGroup(hazeState = hazeState) {
                        MessageState(message = "No extensions available from the current repositories.")
                    }
                }
                else -> {
                    FrostedGroup(hazeState = hazeState) {
                        entries.forEachIndexed { index, entry ->
                            ExtensionEntryContent(
                                entry = entry,
                                installed = entry.id in installedIds,
                                installing = installing[entry.id] == true,
                                error = errors[entry.id],
                                onInstall = {
                                    installing[entry.id] = true
                                    errors[entry.id] = null
                                    scope.launch {
                                        SflxInstaller.install(context, entry)
                                            .onFailure { errors[entry.id] = it.message ?: "Install failed" }
                                        installing[entry.id] = false
                                        refreshInstalled()
                                    }
                                },
                                onUninstall = {
                                    errors[entry.id] = null
                                    scope.launch {
                                        SflxInstaller.uninstall(context, entry.id)
                                            .onFailure { errors[entry.id] = it.message ?: "Uninstall failed" }
                                        refreshInstalled()
                                    }
                                },
                            )
                            if (index < entries.lastIndex) ExtensionDivider()
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(26.dp))
            RepositoriesCard(
                hazeState = hazeState,
                repoUrls = repoUrls,
                onRemove = { url -> AppSettings.setExtensionRepoUrls(repoUrls - url) },
                onAdd = { url ->
                    val trimmed = url.trim()
                    if (trimmed.isNotBlank() && trimmed !in repoUrls) {
                        AppSettings.setExtensionRepoUrls(repoUrls + trimmed)
                    }
                },
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** A frosted, inset card of rows — the app's group-card language, with Haze glass. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FrostedGroup(
    hazeState: HazeState,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (header != null) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(26.dp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                    )
                },
            ),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

/** Divider inset to clear the 40dp provider avatar + 12dp gap in a row. */
@Composable
private fun ExtensionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = ROW_INSET + 40.dp + 12.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** One registry entry: provider avatar, name + version, description, action. */
@Composable
private fun ExtensionEntryContent(
    entry: ExtensionRegistryClient.RegistryEntry,
    installed: Boolean,
    installing: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderAvatar(providerFor(entry))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.display_name.ifBlank { entry.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.version.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    VersionChip(entry.version)
                }
            }
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val chips = buildList {
                if (entry.category.isNotBlank()) add(entry.category)
                addAll(entry.tags)
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.take(6).forEach { ExtensionChip(it) }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (installing) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (installed) {
            TextButton(onClick = onUninstall) { Text("Uninstall") }
        } else {
            Button(onClick = onInstall) { Text("Install") }
        }
    }
}

/** A coloured monogram circle standing in for a provider logo we don't ship. */
@Composable
private fun ProviderAvatar(style: ProviderStyle, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(style.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = style.letter,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/** Maps an entry to a provider identity: distinct accent + initial letter. */
private data class ProviderStyle(val letter: String, val color: Color)

private fun providerFor(entry: ExtensionRegistryClient.RegistryEntry): ProviderStyle {
    val text = buildString {
        append(entry.name, " ", entry.display_name, " ", entry.category, " ", entry.id, " ")
        append(entry.tags.joinToString(" "))
    }.lowercase()
    return when {
        "qobuz" in text -> ProviderStyle("Q", Color(0xFF3B5BDB))
        "tidal" in text -> ProviderStyle("T", Color(0xFF00B4D8))
        "deezer" in text -> ProviderStyle("D", Color(0xFFFF7A00))
        "amazon" in text -> ProviderStyle("A", Color(0xFFFFC107))
        "soundcloud" in text -> ProviderStyle("S", Color(0xFFFF5252))
        "apple" in text -> ProviderStyle("A", Color(0xFFE91E63))
        "pandora" in text -> ProviderStyle("P", Color(0xFF7C4DFF))
        "spotify" in text -> ProviderStyle("S", Color(0xFF1DB954))
        "youtube" in text || "ytmusic" in text -> ProviderStyle("Y", Color(0xFFD50000))
        else -> {
            val ch = (entry.display_name.ifBlank { entry.name }).firstOrNull()?.uppercase() ?: "?"
            ProviderStyle(ch, Color(0xFF607D8B))
        }
    }
}

/** A small pill for an extension's version. */
@Composable
private fun VersionChip(version: String) {
    Text(
        text = version,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A small pill for an extension's category or a tag. */
@Composable
private fun ExtensionChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The repositories section: the current repo URLs with a remove control each,
 * and a row to add a new one. Editing writes straight through via
 * [AppSettings.setExtensionRepoUrls], which the fetch [LaunchedEffect] above
 * observes.
 */
@Composable
private fun RepositoriesCard(
    hazeState: HazeState,
    repoUrls: List<String>,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    FrostedGroup(
        hazeState = hazeState,
        header = "Repositories",
        footer = "Where the store looks for extensions. The official SpotiFLAC " +
            "registry is included by default; add others to browse their extensions.",
    ) {
        repoUrls.forEachIndexed { index, url ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_INSET, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onRemove(url) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Remove repository",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (index < repoUrls.lastIndex) RowDivider()
        }

        var newUrl by remember { mutableStateOf("") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ROW_INSET, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                label = { Text("Add repository URL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    onAdd(newUrl)
                    newUrl = ""
                },
                enabled = newUrl.trim().isNotBlank(),
            ) {
                Text("Add")
            }
        }
    }
}
