package com.geely.online.service

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.geely.online.service.ui.ProfileSwitch
import com.geely.online.service.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.Transparent.toArgb()),
            SystemBarStyle.dark(Color.Transparent.toArgb())
        )

        setContent {
            val uiScale = BuildConfig.UI_SCALE
            val density = LocalDensity.current
            val scaledDensity = remember(density, uiScale) {
                Density(
                    density.density * uiScale,
                    density.fontScale * uiScale
                )
            }

            AppTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    LogScreen(onShare = { shareLogsFile() })
                }
            }
        }
    }

    private fun shareLogsFile() {
        val text = AppLogger.snapshot().joinToString("\n")
        runCatching {
            val file = File(cacheDir, LOG_FILE_NAME)
            file.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData =
                    ClipData.newUri(contentResolver, getString(R.string.share_logs_chooser), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, getString(R.string.share_logs_chooser))
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "log.txt"
    }
}

@Composable
private fun LogScreen(onShare: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val appContext = context.applicationContext
    val logsEnabledFlow = remember(appContext) {
        appContext.settingsDataStore.data
            .map { prefs -> prefs[AppSettingKeys.LOGS_ENABLED] ?: false }
            .distinctUntilChanged()
    }
    val logsEnabled by logsEnabledFlow.collectAsStateWithLifecycle(initialValue = false)

    var logText by remember { mutableStateOf("") }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val snapshot = withContext(Dispatchers.Default) {
                    AppLogger.snapshot().joinToString("\n")
                }
                logText = snapshot
                delay(LogActivityRefreshIntervalMs)
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.surfaceBackground)
                .padding(innerPadding)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                color = AppTheme.colors.surfaceBackground,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 6.dp, end = 20.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    appContext.settingsDataStore.edit { prefs ->
                                        prefs[AppSettingKeys.LOGS_ENABLED] = !logsEnabled
                                    }
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier
                                .padding(horizontal = 23.dp),
                            text = stringResource(R.string.log_enable_title),
                            style = AppTheme.typography.screenTitle,
                            color = AppTheme.colors.contentPrimary
                        )
                        ProfileSwitch(
                            scale = 0.8f,
                            checked = logsEnabled,
                            onCheckedChange = null
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onShare,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = AppTheme.colors.contentPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.share_logs_button),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = AppTheme.colors.surfaceMenuDivider,
                thickness = 1.dp
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceSettingsLayer1)
            ) {
                SelectionContainer {
                    Text(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        text = logText,
                        style = AppTheme.typography.dialogSubtitle.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = AppTheme.colors.contentPrimary
                    )
                }
            }
        }
    }
}

private const val LogActivityRefreshIntervalMs = 2000L
