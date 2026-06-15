package com.lorenzl5.sharerouter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorenzl5.sharerouter.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onOpenHistory: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val persisted by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    var specUrl by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf<String?>(null) }
    var issuer by remember { mutableStateOf<String?>(null) }
    var clientId by remember { mutableStateOf<String?>(null) }
    var scopes by remember { mutableStateOf<String?>(null) }
    var exchangeId by remember { mutableStateOf<String?>(null) }

    // Seed the editable fields from persisted settings exactly once.
    LaunchedEffect(persisted) {
        if (specUrl == null) {
            specUrl = persisted.specUrl
            baseUrl = persisted.baseUrlOverride
            issuer = persisted.oidcIssuer
            clientId = persisted.oidcClientId
            scopes = persisted.oidcScopes
            exchangeId = persisted.exchangeClientId
        }
    }

    fun current() = AppSettings(
        specUrl = specUrl.orEmpty(),
        baseUrlOverride = baseUrl.orEmpty(),
        oidcIssuer = issuer.orEmpty(),
        oidcClientId = clientId.orEmpty(),
        oidcScopes = scopes.orEmpty().ifBlank { com.lorenzl5.sharerouter.data.settings.Defaults.OIDC_SCOPES },
        exchangeClientId = exchangeId.orEmpty(),
    )

    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(vm.isAuthorized()) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) {
            status = "Login cancelled"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            status = try {
                vm.handleLoginResult(data)
                authorized = vm.isAuthorized()
                if (authorized) "Logged in ✓" else "Login did not complete"
            } catch (e: Exception) {
                "Login failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share Router") },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1) Login (Authentik) — primary first step, OIDC config tucked below.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !busy && current().usesAuth,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = ""
                            try {
                                val intent = vm.buildLoginIntent(current())
                                loginLauncher.launch(intent)
                            } catch (e: Exception) {
                                status = "Could not start login: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (authorized) "Re-login Authentik" else "Login Authentik") }

                if (authorized) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { vm.logout(); authorized = false; status = "Logged out" },
                    ) { Text("Logout") }
                }
            }

            ExpandableSection("config Authentik / OIDC") {
                Field(
                    "Issuer URL", issuer.orEmpty(), KeyboardType.Uri,
                    supporting = "e.g. https://auth.lorenzl5.com/application/o/share-router/",
                ) { issuer = it }
                Field("Client ID", clientId.orEmpty(), KeyboardType.Text) { clientId = it }
                Field("Scopes", scopes.orEmpty(), KeyboardType.Text) { scopes = it }
                Field(
                    "Token-Exchange Client ID (optional)", exchangeId.orEmpty(), KeyboardType.Text,
                    supporting = "Leave blank — auto-discovered from the forwardAuth redirect of the API host. Fill only to override.",
                ) { exchangeId = it }
                Text(
                    "Redirect URI to register in Authentik:  sharerouter:/oauth2redirect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // 2) Reachability check — "Tailscale aktiv?" + Test, MasterAPI config below.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tailscale aktiv?", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    enabled = !busy && specUrl?.isNotBlank() == true,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Testing…"
                            status = try {
                                vm.test(current())
                            } catch (e: Exception) {
                                "Test failed: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Test") }
            }

            ExpandableSection("config MasterAPI") {
                Field("OpenAPI spec URL", specUrl.orEmpty(), KeyboardType.Uri) { specUrl = it }
                Field(
                    "Base URL override (optional)", baseUrl.orEmpty(), KeyboardType.Uri,
                    supporting = "Leave blank to use servers[0].url from the spec.",
                ) { baseUrl = it }
                Button(
                    enabled = !busy && specUrl?.isNotBlank() == true,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Saved ✓"
                            vm.save(current())
                            busy = false
                        }
                    },
                ) { Text("Save") }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                }
            }

            if (status.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        status,
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.size(8.dp))
            Text(
                "Share any content to “Share Router” from another app to route it to a matching MasterAPI endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Collapsed-by-default config panel with a tappable header + chevron. */
@Composable
private fun ExpandableSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboard,
            imeAction = ImeAction.Next,
        ),
        supportingText = supporting?.let { { Text(it) } },
    )
}
