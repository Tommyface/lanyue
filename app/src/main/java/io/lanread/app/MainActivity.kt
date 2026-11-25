package io.lanread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.lanread.app.ui.AppNav
import io.lanread.app.ui.LanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LanTheme {
                AppNav()
            }
        }
    }
}
