package io.lanread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.lanread.app.ui.AppNav
import io.lanread.app.ui.LanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // v0.4.23：状态栏沉浸——内容延伸到系统栏后面（图标颜色随主题自动深/浅）
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LanTheme {
                AppNav()
            }
        }
    }
}
