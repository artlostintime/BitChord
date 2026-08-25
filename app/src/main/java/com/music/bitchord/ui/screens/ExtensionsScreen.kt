package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.extensions.ExtensionRegistryClient
import com.music.bitchord.data.extensions.SflxInstaller
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SongRowSkeleton
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
            Text(
                text = "Extension store",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            )
        }

        item {
            when {
                loading -> {
                    Column {
                        repeat(4) { SongRowSkeleton(index = it) }
                    }
                }
                loadError != null -> {
                    MessageState(message = loadError!!)
                }
                entries.isEmpty() -> {
                    MessageState(message = "No extensions available from the current repositories.")
                }
                else -> {
                    SettingsGroup {
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
                            if (index < entries.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(26.dp))
            RepositoriesCard(
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

/** One registry entry: name, version, description, chips, and an action button. */
@Composable
private fun ExtensionEntryContent(
    entry: ExtensionRegistryClient.RegistryEntry,
    installed: Boolean,
    installing: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.display_name.ifBlank { entry.name },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (entry.version.isNotBlank()) {
                Text(
                    text = entry.version,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                Button(onClick = onUninstall) { Text("Uninstall") }
            } else {
                Button(onClick = onInstall, enabled = !installing) {
                    Text(if (installing) "Installing…" else "Install")
                }
            }
            if (error != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
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
            .background(MaterialTheme.colorScheme.background)
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
    repoUrls: List<String>,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    SettingsGroup(
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
