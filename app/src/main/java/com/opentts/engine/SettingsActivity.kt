package com.opentts.engine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Configuration screen. It is also the activity the system opens from
 * Settings > Text-to-speech output (see res/xml/tts_engine.xml).
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            OpenTtsTheme {
                SettingsScreen(store)
            }
        }
    }
}
