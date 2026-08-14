package com.collectionfield.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collectionfield.app.ui.navigation.CollectionFieldNavHost
import com.collectionfield.app.ui.theme.CollectionFieldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CollectionFieldApplication).container
        setContent {
            val isDarkMode by container.themePreferences.isDarkMode.collectAsStateWithLifecycle()
            CollectionFieldTheme(darkTheme = isDarkMode) {
                CollectionFieldNavHost(container = container)
            }
        }
    }
}
