package com.opentts.engine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opentts.engine.server.ClientCertStore
import com.opentts.engine.server.ServerClient
import com.opentts.engine.server.ServerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Settings for a self-hosted OpenTTS-SRV instance: URL, bearer token, model and
 * voice discovered from `/v1/models`, plus optional mutual-TLS client certs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSection(
    settings: TtsSettings,
    onUpdate: (TtsSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<ServerModel>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var certPassword by remember { mutableStateOf("") }
    var certInfo by remember { mutableStateOf(ClientCertStore.description(context)) }

    fun refresh() {
        if (settings.serverUrl.isBlank()) {
            status = "Enter the server URL first, e.g. http://192.168.1.10:8420"
            return
        }
        scope.launch {
            busy = true
            status = "Contacting ${settings.serverUrl}…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = ServerClient(
                        baseUrl = settings.serverUrl,
                        token = settings.serverToken,
                        certs = ClientCertStore.load(context),
                    )
                    val health = JSONObject(client.health())
                    val list = client.listModels()
                    Triple(health.optString("device", "?"), health.optBoolean("cuda", false), list)
                }
            }
            busy = false
            result.onSuccess { (device, cuda, list) ->
                models = list
                val current = list.firstOrNull { it.id == settings.serverModel } ?: list.firstOrNull()
                if (current != null) {
                    val voice = current.voices.firstOrNull { it.id == settings.serverVoice } ?: current.voices.firstOrNull()
                    onUpdate(
                        settings.copy(
                            serverModel = current.id,
                            serverVoice = voice?.id.orEmpty(),
                            localModelId = settings.localModelId,
                        ),
                    )
                }
                status = "Connected · ${list.size} models · device $device${if (cuda) " (CUDA)" else " (CPU)"}"
            }.onFailure {
                models = emptyList()
                status = "Connection failed: ${it.message}"
            }
        }
    }

    val selected = models.firstOrNull { it.id == settings.serverModel }
    val voiceOptions = selected?.voices.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("OpenTTS server", style = MaterialTheme.typography.titleSmall)
        Text(
            "Synthesizes on a machine with a GPU (ONNX Runtime + CUDA). The phone only sends text " +
                "and receives PCM, so the model and its voices live on the server.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = settings.serverUrl,
            onValueChange = { onUpdate(settings.copy(serverUrl = it.trim())) },
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.10:8420") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.serverToken,
            onValueChange = { onUpdate(settings.copy(serverToken = it.trim())) },
            label = { Text("Bearer token") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { refresh() }, enabled = !busy) {
                Text(if (busy) "Connecting…" else "Connect / refresh")
            }
            if (models.isNotEmpty()) {
                Text("${models.size} models", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (models.isNotEmpty()) {
            DropdownField(
                label = "Model",
                options = models.map { "${it.label} [${it.id}]" },
                selected = selected?.let { "${it.label} [${it.id}]" } ?: "—",
                onSelect = { label ->
                    val id = label.substringAfterLast('[').substringBeforeLast(']')
                    val model = models.firstOrNull { it.id == id }
                    onUpdate(
                        settings.copy(
                            serverModel = id,
                            serverVoice = model?.voices?.firstOrNull()?.id.orEmpty(),
                        ),
                    )
                },
            )
            DropdownField(
                label = "Voice (${voiceOptions.size})",
                options = voiceOptions.map { "${it.label} [${it.id}]" },
                selected = voiceOptions.firstOrNull { it.id == settings.serverVoice }
                    ?.let { "${it.label} [${it.id}]" } ?: "—",
                onSelect = { label ->
                    val id = label.substringAfterLast('[').substringBeforeLast(']')
                    onUpdate(settings.copy(serverVoice = id))
                },
            )
            if (selected != null && !selected.installed) {
                Text(
                    "This model is not downloaded on the server yet. The server fetches it on the " +
                        "first request (90–330 MB), which makes that first utterance slow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Mutual TLS (optional)", style = MaterialTheme.typography.labelLarge)
                Text(
                    certInfo ?: "No client certificate. Needed only when the server sits behind " +
                        "Caddy/NPM requiring a client cert.",
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = certPassword,
                    onValueChange = { certPassword = it },
                    label = { Text("PKCS#12 password (if any)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        // Picked URIs are MediaStore/SAF ones, so the filename has to
                        // come from the provider; lastPathSegment is often just an id.
                        val name = runCatching {
                            context.contentResolver.query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null,
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        if (bytes == null) {
                            status = "Could not read the selected file"
                            return@launch
                        }
                        val imported = withContext(Dispatchers.IO) {
                            // Sniff the content as well as the name: a PKCS#12 file is
                            // DER (starts with 0x30), a PEM starts with "-----BEGIN".
                            val looksLikeP12 = name.endsWith(".p12", true) ||
                                name.endsWith(".pfx", true) ||
                                (bytes.isNotEmpty() && bytes[0] == 0x30.toByte())
                            if (looksLikeP12) {
                                ClientCertStore.importPkcs12(context, bytes, certPassword)
                            } else {
                                ClientCertStore.importPem(context, bytes, certPassword)
                            }
                        }
                        imported.onSuccess {
                            certInfo = ClientCertStore.description(context)
                            status = it
                        }.onFailure {
                            status = "Import failed: ${it.message}"
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("Import .p12 / .pem")
                    }
                    if (certInfo != null) {
                        TextButton(onClick = {
                            ClientCertStore.clear(context)
                            certInfo = null
                            status = "Client certificate removed"
                        }) { Text("Remove") }
                    }
                }
                Text(
                    "PEM must contain an unencrypted PKCS#8 PRIVATE KEY plus the certificate chain. " +
                        "Extra certificates in the file are trusted as a private CA.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
