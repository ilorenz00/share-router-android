package com.lorenzl5.sharerouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lorenzl5.sharerouter.data.InputKind
import com.lorenzl5.sharerouter.data.SharedContent
import com.lorenzl5.sharerouter.data.openapi.Endpoint

@Composable
fun ShareSheetScreen(
    vm: ShareViewModel,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            ContentPreview(vm.sharedContent)
            Spacer(Modifier.size(16.dp))

            when (val s = state) {
                is ShareUiState.Loading -> Centered { CircularProgressIndicator() }

                is ShareUiState.NeedsConfig -> StatusBlock(
                    title = "Not configured yet",
                    body = "Set your MasterAPI OpenAPI URL in settings first.",
                    actionLabel = "Open settings",
                    onAction = onOpenSettings,
                )

                is ShareUiState.NoMatch -> StatusBlock(
                    title = "No matching endpoint",
                    body = "${s.specTitle} has no operation that accepts: ${s.kinds}.",
                    actionLabel = "Close",
                    onAction = onDismiss,
                )

                is ShareUiState.Error -> StatusBlock(
                    title = "Something went wrong",
                    body = s.message,
                    actionLabel = "Retry",
                    onAction = vm::load,
                )

                is ShareUiState.Ready -> {
                    Text(s.specTitle, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        items(s.endpoints) { ep ->
                            EndpointCard(ep) { vm.dispatch(ep) }
                        }
                    }
                }

                is ShareUiState.Sending -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("Sending to ${s.endpoint.title}…")
                    }
                }

                is ShareUiState.Done -> StatusBlock(
                    title = if (s.result.ok) "✓ ${s.endpoint.title}" else "✗ ${s.endpoint.title}",
                    body = s.result.message,
                    actionLabel = "Done",
                    onAction = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ContentPreview(content: SharedContent) {
    val first = content.items.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (first?.kind == InputKind.IMAGE && first.uri != null) {
            AsyncImage(
                model = first.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Icon(
                imageVector = iconFor(first?.kind),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = content.kinds.joinToString { it.name.lowercase() }.ifBlank { "unknown" },
                style = MaterialTheme.typography.titleMedium,
            )
            val detail = content.primaryText ?: first?.uri?.lastPathSegment ?: ""
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EndpointCard(ep: Endpoint, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ep.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${ep.method} ${ep.path}  ·  ${ep.accepts.joinToString { it.name.lowercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ep.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StatusBlock(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        androidx.compose.material3.Button(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) { content() }
}

private fun iconFor(kind: InputKind?) = when (kind) {
    InputKind.IMAGE -> Icons.Default.Image
    InputKind.VIDEO -> Icons.Default.Videocam
    InputKind.AUDIO -> Icons.Default.Audiotrack
    InputKind.URL -> Icons.Default.Link
    InputKind.TEXT -> Icons.Default.Description
    else -> Icons.Default.AttachFile
}
