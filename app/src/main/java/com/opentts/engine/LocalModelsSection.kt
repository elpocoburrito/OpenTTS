package com.opentts.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentts.engine.onnx.LocalModelCatalog
import com.opentts.engine.onnx.LocalModelSpec
import com.opentts.engine.onnx.LocalTtsEngine
import com.opentts.engine.onnx.ModelStore
import com.opentts.engine.onnx.WavIo
import kotlinx.coroutines.launch

/**
 * Local (on-device) model management: install from Hugging Face, pick a voice,
 * remove. All downloads land in the app's private files dir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsSection(
    settings: TtsSettings,
    onUpdate: (TtsSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ModelStore(context) }
    val progress by store.progress.collectAsState()

    // Re-read disk state after installs/deletes.
    var diskEpoch by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val selectedModel = LocalModelCatalog.find(settings.localModelId) ?: LocalModelCatalog.kokoro
    val voiceOptions = selectedModel.voices

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Local models", style = MaterialTheme.typography.titleSmall)
        Text(
            "Runs entirely on this device through ONNX Runtime: no API key, no network, " +
                "nothing leaves the phone. Models are downloaded once and stored in app data.",
            style = MaterialTheme.typography.bodySmall,
        )

        for (model in LocalModelCatalog.all) {
            // Referencing diskEpoch keeps this recomposing after installs.
            @Suppress("UNUSED_EXPRESSION")
            diskEpoch
            ModelCard(
                model = model,
                installed = store.isInstalled(model),
                installedBytes = store.installedBytes(model),
                progress = progress[model.id],
                selected = model.id == settings.localModelId,
                onInstall = {
                    scope.launch {
                        status = "Downloading ${model.label}…"
                        runCatching { store.install(model) }
                            .onSuccess { status = "${model.label} installed." }
                            .onFailure { status = "Download failed: ${it.message}" }
                        diskEpoch++
                    }
                },
                onDelete = {
                    store.delete(model)
                    diskEpoch++
                    status = "${model.label} removed."
                },
                onSelect = {
                    onUpdate(
                        settings.copy(
                            localModelId = model.id,
                            localVoiceId = model.voices.first().id,
                        ),
                    )
                },
            )
        }

        HorizontalDivider()

        DropdownField(
            label = "Local voice (${voiceOptions.size})",
            options = voiceOptions.map { "${it.label} [${it.id}]" },
            selected = voiceOptions.firstOrNull { it.id == settings.localVoiceId }
                ?.let { "${it.label} [${it.id}]" }
                ?: voiceOptions.first().let { "${it.label} [${it.id}]" },
            onSelect = { label ->
                val id = label.substringAfterLast('[').substringBeforeLast(']')
                onUpdate(settings.copy(localVoiceId = id))
            },
        )

        val voice = voiceOptions.firstOrNull { it.id == settings.localVoiceId } ?: voiceOptions.first()
        if (voice.remote != null && !store.isVoiceReady(selectedModel, voice)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        status = "Downloading voice ${voice.label}…"
                        runCatching { store.ensureVoice(selectedModel, voice) }
                            .onSuccess { status = "Voice ${voice.label} ready." }
                            .onFailure { status = "Voice download failed: ${it.message}" }
                        diskEpoch++
                    }
                },
            ) {
                Text("Download voice · ${android.text.format.Formatter.formatShortFileSize(context, voice.remote.bytes)}")
            }
        }

        Text(
            "Sample rate ${selectedModel.sampleRate} Hz · ${selectedModel.family.label} · " +
                "licence ${selectedModel.license}",
            style = MaterialTheme.typography.bodySmall,
        )

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (BuildConfig.DEBUG) {
            // Debug-only self test: renders a phrase straight through the same
            // engine the TTS service uses and writes it to a WAV file that can be
            // pulled with `adb pull`.
            OutlinedButton(
                onClick = {
                    scope.launch {
                        status = "Rendering test WAV…"
                        val result = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val engine = LocalTtsEngine(context, store)
                                val chunks = ArrayList<FloatArray>()
                                val rate = engine.speak(
                                    "OpenTTS NG runs Kokoro locally, on the phone, with no network at all. " +
                                        "This second sentence checks that streaming works across chunks. " +
                                        "And this third one should finish the test cleanly.",
                                    selectedModel,
                                    voice,
                                    1.0f,
                                ) { chunks.add(it); true }
                                val all = chunks.flatMap { it.toList() }.toFloatArray()
                                val out = java.io.File(context.getExternalFilesDir("debug"), "selftest.wav")
                                WavIo.write(out, all, rate)
                                out to all.size
                            }
                        }
                        status = result.fold(
                            { (file, samples) -> "Wrote ${file.absolutePath} (${samples / 24000.0}s)" },
                            { "Render failed: ${it.message}" },
                        )
                    }
                },
                enabled = store.isVoiceReady(selectedModel, voice),
            ) {
                Text("Render test WAV (debug)")
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModelSpec,
    installed: Boolean,
    installedBytes: Long,
    progress: ModelStore.Progress?,
    selected: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, style = MaterialTheme.typography.titleSmall)
                    Text(model.description, style = MaterialTheme.typography.bodySmall)
                }
                if (selected) {
                    Text("in use", style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed) {
                    Text(
                        android.text.format.Formatter.formatShortFileSize(context, installedBytes) + " on disk",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onSelect) { Text("Use") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                } else if (progress != null) {
                    Column(Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "%.0f%% · %s".format(
                                progress.fraction * 100,
                                android.text.format.Formatter.formatShortFileSize(context, progress.received),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    Button(onClick = onInstall) {
                        Text(
                            "Download · " +
                                android.text.format.Formatter.formatShortFileSize(context, model.baseBytes),
                        )
                    }
                }
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
