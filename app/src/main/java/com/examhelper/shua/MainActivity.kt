package com.examhelper.shua

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.examhelper.shua.data.update.UpdateChecker
import com.examhelper.shua.data.update.UpdateInfo
import com.examhelper.shua.ui.AppRoot
import com.examhelper.shua.ui.sound.Haptics
import com.examhelper.shua.ui.settings.UpdateAvailableDialog
import com.examhelper.shua.ui.theme.ShuaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by remember { ShuaApp.instance.container.settings.settings }
                .collectAsState(initial = com.examhelper.shua.data.settings.AppSettings())
            var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
            val uriHandler = LocalUriHandler.current
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { UpdateChecker.latest() } }
                    .getOrNull()
                    ?.takeIf {
                        UpdateChecker.isUpdateAvailable(
                            currentVersion = BuildConfig.VERSION_NAME,
                            currentCode = BuildConfig.VERSION_CODE,
                            latestVersion = it.version,
                            latestCode = it.versionCode
                        )
                    }
                    ?.let { updateInfo = it }
            }
            // 震动总开关跟设置同步（做题页在判分时同步读取）
            Haptics.setEnabled(settings.hapticEnabled)
            Haptics.setStrength(settings.hapticStrength)
            Haptics.setDuration(settings.hapticDuration)
            ShuaTheme(darkTheme = settings.darkMode) {
                AppRoot(fontScale = settings.fontScale)
                updateInfo?.let { info ->
                    UpdateAvailableDialog(
                        info = info,
                        onDismiss = { updateInfo = null },
                        onUpdate = {
                            uriHandler.openUri(info.downloadUrl.ifBlank { info.releaseUrl })
                            updateInfo = null
                        },
                        onFallback = info.fallbackDownloadUrl?.let { url ->
                            {
                                uriHandler.openUri(url)
                                updateInfo = null
                            }
                        }
                    )
                }
            }
        }
    }
}
