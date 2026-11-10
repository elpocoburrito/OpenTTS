package com.opentts.engine

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opentts.engine.onnx.Phonemizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "OpenTTS"

private const val TEST_PHRASE =
    "Hello from OpenTTS. This is a test of the OpenRouter speech engine."

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(store.load()) }
    var models by remember { mutableStateOf(ModelsRepository.fromJson(settings.catalogJson)) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }

    fun update(new: TtsSettings) {
        settings = new
        store.save(new)
    }

    fun refreshModels() {
        scope.launch {
            loading = true
            status = "Fetching TTS models from OpenRouter…"
            try {
                val fetched = withContext(Dispatchers.IO) {
                    ModelsRepository.fetchCatalog(settings.baseUrl, settings.apiKey)
                }
                if (fetched.isEmpty()) {
                    status = "No TTS models were returned."
                } else {                    models = fetched
                    val catalog = ModelsRepository.toJson(fetched)
                    var updated = settings.copy(catalogJson = catalog)
                    if (fetched.none { it.id == updated.model }) {
                        val first = fetched.first()
                        updated = updated.copy(
                            model = first.id,
                            voice = first.voices.firstOrNull() ?: "",
                        )
                    }
                    update(updated)
                    status = "Loaded ${fetched.size} text-to-speech models."
                    Log.i(TAG, "Loaded ${fetched.size} TTS models")
                }
            } catch (e: Exception) {
                status = "Could not load models: ${e.message}"
                Log.e(TAG, "Could not load models", e)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (models.isEmpty()) refreshModels()
    }

    val selectedModel = models.firstOrNull { it.id == settings.model }
    val voiceOptions = selectedModel?.voices.orEmpty()

    Scaffold(
        topBar = { TopAppBar(title = { Text("OpenTTS NG") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Text-to-speech engine: OpenRouter cloud models or local ONNX models",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Select OpenTTS in Settings → Text-to-speech output, then choose a voice in your " +
                    "reading app (e.g. Evie).",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Backend", style = MaterialTheme.typography.labelLarge)
            // Three chips do not fit one row on a phone; FlowRow wraps them.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = settings.backend == TtsSettings.BACKEND_LOCAL,
                    onClick = { update(settings.copy(backend = TtsSettings.BACKEND_LOCAL)) },
                    label = { Text("On device (ONNX)") },
                )
                FilterChip(
                    selected = settings.backend == TtsSettings.BACKEND_CLOUD,
                    onClick = { update(settings.copy(backend = TtsSettings.BACKEND_CLOUD)) },
                    label = { Text("OpenRouter (cloud)") },
                )
                FilterChip(
                    selected = settings.backend == TtsSettings.BACKEND_SERVER,
                    onClick = { update(settings.copy(backend = TtsSettings.BACKEND_SERVER)) },
                    label = { Text("Self-hosted server") },
                )
            }

            if (settings.backend == TtsSettings.BACKEND_LOCAL) {
                LocalModelsSection(settings) { update(it) }
            } else if (settings.backend == TtsSettings.BACKEND_SERVER) {
                ServerSection(settings) { update(it) }
            } else {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { update(settings.copy(apiKey = it.trim())) },
                    label = { Text("OpenRouter API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                DropdownField(
                    label = "Model",
                    options = models.map { it.id },
                    selected = settings.model,
                    onSelect = { id ->
                        val voice = models.firstOrNull { it.id == id }?.voices?.firstOrNull() ?: ""
                        update(settings.copy(model = id, voice = voice))
                    },
                )

                DropdownField(
                    label = "Voice (${voiceOptions.size} available)",
                    options = voiceOptions,
                    selected = settings.voice,
                    onSelect = { update(settings.copy(voice = it)) },
                )

                Text("Audio format", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = settings.format == TtsSettings.FORMAT_MP3,
                        onClick = { update(settings.copy(format = TtsSettings.FORMAT_MP3)) },
                        label = { Text("MP3 (reliable)") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = settings.format == TtsSettings.FORMAT_PCM,
                        onClick = { update(settings.copy(format = TtsSettings.FORMAT_PCM)) },
                        label = { Text("PCM (low latency)") },
                    )
                }
                Text(
                    if (settings.format == TtsSettings.FORMAT_PCM) {
                        "Raw PCM is streamed with no decoding. The sample rate below must match the " +
                            "model's output (most use 24000 Hz)."
                    } else {
                        "MP3 is decoded on device, so the sample rate is detected automatically."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.format == TtsSettings.FORMAT_PCM) {
                    OutlinedTextField(
                        value = settings.sampleRate.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { update(settings.copy(sampleRate = it)) }
                        },
                        label = { Text("PCM sample rate (Hz)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text(
                "Speed: ${"%.2f".format(settings.speed)}×",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = settings.speed,
                onValueChange = { update(settings.copy(speed = it)) },
                valueRange = 0.5f..2.0f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.useClientRate,
                    onCheckedChange = { update(settings.copy(useClientRate = it)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Also apply the reading app's speed setting",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (settings.backend == TtsSettings.BACKEND_CLOUD) {
                Text(
                    "Some models reject a non-default speed. Leave the slider at 1.00× if you see errors.",
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = settings.instructions,
                    onValueChange = { update(settings.copy(instructions = it)) },
                    label = { Text("Delivery instructions (optional)") },
                    placeholder = { Text("e.g. warm and friendly") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.backend == TtsSettings.BACKEND_CLOUD) {
                    Button(onClick = { refreshModels() }, enabled = !loading) {
                        Text(if (loading) "Loading…" else "Refresh models")
                    }
                }
                OutlinedButton(onClick = { openTtsSettings(context) }) {
                    Text("System settings")
                }
            }

            TestSection()

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()

            Text(
                if (settings.backend == TtsSettings.BACKEND_LOCAL) "Local engine" else "Advanced",
                style = MaterialTheme.typography.labelLarge,
            )
            if (settings.backend == TtsSettings.BACKEND_CLOUD) {
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { update(settings.copy(baseUrl = it.trim())) },
                label = { Text("API base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.timeoutSec.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { update(settings.copy(timeoutSec = it)) }
                },
                label = { Text("Request timeout (seconds)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            } else {
                Text(
                    "espeak-ng " + Phonemizer.version() + " · ONNX Runtime, CPU, up to 4 threads",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))
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
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
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
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("—") },
                    onClick = { expanded = false },
                )
            } else {
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
}

@Composable
private fun TestSection() {
    val context = LocalContext.current
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val engine = TextToSpeech(
            context,
            { status -> ready = status == TextToSpeech.SUCCESS },
            context.packageName,
        )
        tts = engine
        onDispose { engine.shutdown() }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                tts?.apply {
                    language = Locale.US
                    speak(TEST_PHRASE, TextToSpeech.QUEUE_FLUSH, null, "opentts-test")
                }
            },
            enabled = ready,
        ) {
            Text("Test voice")
        }
        if (!ready) {
            Text("Initialising engine…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun openTtsSettings(context: Context) {
    val candidates = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = intent.resolveActivity(context.packageManager) != null
        if (resolvable && runCatching { context.startActivity(intent) }.isSuccess) {
            return
        }
    }
}
