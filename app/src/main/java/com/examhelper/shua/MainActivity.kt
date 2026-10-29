package com.examhelper.shua

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.examhelper.shua.ui.AppRoot
import com.examhelper.shua.ui.sound.Haptics
import com.examhelper.shua.ui.theme.ShuaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by remember { ShuaApp.instance.container.settings.settings }
                .collectAsState(initial = com.examhelper.shua.data.settings.AppSettings())
            // 震动总开关跟设置同步（做题页在判分时同步读取）
            Haptics.setEnabled(settings.hapticEnabled)
            Haptics.setStrength(settings.hapticStrength)
            ShuaTheme(darkTheme = settings.darkMode) {
                AppRoot(fontScale = settings.fontScale)
            }
        }
    }
}
