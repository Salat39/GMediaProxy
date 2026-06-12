@file:Suppress("SameParameterValue")

package com.geely.online.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geely.online.service.ui.BaseButton
import com.geely.online.service.ui.RenderSwitcher
import com.geely.online.service.ui.spannedFromHtml
import com.geely.online.service.ui.theme.AppTheme
import com.geely.online.service.ui.toAnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val DISPLAY_AS_APP_SOURCE_KEYS = listOf(
    "flow",
    "net_easy_cloud",
    "kugou",
    "kuwo",
    "fandeng",
    "jinri",
    "sohu",
    "xmly",
//    "aiqiyi",
//    "G-C",
//    "tencent_video",
//    "bilibili",
//    "huoshan",
//    "thunder_voice",
//    "cmvideo",
)

private val DISPLAY_AS_APP_SOURCE_LABELS = mapOf(
    "flow" to "WeCarFlow",
    "net_easy_cloud" to "NetEase Cloud",
    "kugou" to "KuGou",
    "kuwo" to "KuWo",
    "fandeng" to "Fanshu",
    "jinri" to "Toutiao",
    "sohu" to "Sohu News",
    "xmly" to "Ximalaya",
    "aiqiyi" to "iQIYI",
    "G-C" to "G-C",
    "tencent_video" to "Tencent Video",
    "bilibili" to "Bilibili",
    "huoshan" to "Huoshan",
    "thunder_voice" to "Thunder Voice",
    "cmvideo" to "CMVideo",
)

private fun displayAsAppSourceLabel(key: String): String =
    DISPLAY_AS_APP_SOURCE_LABELS[key] ?: key

private fun displayAsAppSourceDrawableRes(key: String): Int? = when (key) {
    "flow" -> R.drawable.common_source_online
    "net_easy_cloud" -> R.drawable.common_source_cloud
    "kugou" -> R.drawable.common_source_kugou
    "kuwo" -> R.drawable.common_source_kuwo
    "fandeng" -> R.drawable.common_source_fandeng
    "jinri" -> R.drawable.common_source_jinri
    "sohu" -> R.drawable.common_source_souhu
    "xmly" -> R.drawable.common_source_xmly
    else -> null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.Transparent.toArgb()),
            SystemBarStyle.dark(Color.Transparent.toArgb())
        )

        if (!isNotificationListenerEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

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
                    MainScreen()
                }
            }
        }
    }
}

private fun Context.isNotificationListenerEnabled(): Boolean {
    val expected = ComponentName(this, MediaSessionNotificationListenerService::class.java)
    val flat = Settings.Secure.getString(
        contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.split(':')
        .mapNotNull { ComponentName.unflattenFromString(it) }
        .any { it == expected }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    var notificationListenerEnabled by remember {
        mutableStateOf(context.isNotificationListenerEnabled())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationListenerEnabled = context.isNotificationListenerEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.surfaceBackground)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!notificationListenerEnabled) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.grant_notification_access),
                        textAlign = TextAlign.Center,
                        style = AppTheme.typography.screenTitle.copy(
                            lineHeight = 23.sp
                        ),
                        color = AppTheme.colors.contentPrimary
                    )
                    Spacer(Modifier.height(36.dp))
                    BaseButton(
                        title = stringResource(R.string.settings),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    )
                }
            } else {
                MainSettingsContent(uiScaleState = BuildConfig.UI_SCALE)
            }
        }
    }
}

@Suppress("AssignedValueIsNeverRead")
@Composable
private fun MainSettingsContent(uiScaleState: Float) {
    val activityContext = LocalContext.current
    val appContext = activityContext.applicationContext
    val scope = rememberCoroutineScope()
    val displayAsKeyFlow = remember(appContext) {
        appContext.settingsDataStore.data
            .map { prefs -> prefs[AppSettingKeys.DISPLAY_AS_APP_SOURCE_KEY] ?: "flow" }
            .distinctUntilChanged()
    }
    val displayAsKey by displayAsKeyFlow.collectAsStateWithLifecycle(initialValue = "flow")
    val displayLikeInsteadOfPreviousFlow = remember(appContext) {
        appContext.settingsDataStore.data
            .map { prefs -> prefs[AppSettingKeys.DISPLAY_LIKE_INSTEAD_OF_PREVIOUS] ?: false }
            .distinctUntilChanged()
    }
    val displayLikeInsteadOfPrevious by displayLikeInsteadOfPreviousFlow.collectAsStateWithLifecycle(
        initialValue = OnlineMusicSettingsProvider.currentIsFavorAble()
    )
    val likeModeFlow = remember(appContext) {
        appContext.settingsDataStore.data
            .map { prefs -> prefs[AppSettingKeys.LIKE_MODE] ?: 0 }
            .distinctUntilChanged()
    }
    val likeModeInt by likeModeFlow.collectAsStateWithLifecycle(
        initialValue = OnlineMusicSettingsProvider.currentLikeMode()
    )
    var showDisplayAsDialog by remember { mutableStateOf(false) }
    var showLikeModeDialog by remember { mutableStateOf(false) }
    var showBroadcastHint by remember { mutableStateOf(false) }

    if (showDisplayAsDialog) {
        DisplayAsAppSourceDialog(
            initialKey = displayAsKey,
            uiScaleState = uiScaleState,
            onDismiss = { showDisplayAsDialog = false },
            onCancel = { showDisplayAsDialog = false },
            onConfirmed = { key ->
                scope.launch(Dispatchers.IO) {
                    appContext.settingsDataStore.edit { prefs ->
                        prefs[AppSettingKeys.DISPLAY_AS_APP_SOURCE_KEY] = key
                    }
                    delay(350L)
                    MediaSessionPipeline.get().refresh()
                }
                showDisplayAsDialog = false
            }
        )
    }

    if (showLikeModeDialog) {
        LikeModeDialog(
            initialLikeModeInt = likeModeInt,
            uiScaleState = uiScaleState,
            onDismiss = { showLikeModeDialog = false },
            onCancel = { showLikeModeDialog = false },
            onConfirmed = { mode ->
                scope.launch(Dispatchers.IO) {
                    appContext.settingsDataStore.edit { prefs ->
                        prefs[AppSettingKeys.LIKE_MODE] = mode.toIntMode
                    }
                }
                showLikeModeDialog = false
            }
        )
    }

    Spacer(Modifier.height(26.dp))

    Text(
        modifier = Modifier,
        text = stringResource(R.string.service_active),
        style = AppTheme.typography.dialogTitle,
        color = AppTheme.colors.contentAccent
    )

    Spacer(Modifier.height(48.dp))

    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { showDisplayAsDialog = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                modifier = Modifier.padding(horizontal = 23.dp),
                text = stringResource(R.string.display_as_title),
                style = AppTheme.typography.screenTitle,
                color = AppTheme.colors.contentPrimary
            )

            Spacer(Modifier.height(5.dp))

            Text(
                text = stringResource(R.string.display_as_subtitle),
                modifier = Modifier.padding(horizontal = 23.dp),
                color = AppTheme.colors.contentPrimary.copy(alpha = 0.4f),
                style = AppTheme.typography.dialogSubtitle
            )
        }

        Text(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(AppTheme.colors.autoStart)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            text = displayAsAppSourceLabel(displayAsKey),
            color = AppTheme.colors.contentPrimary,
            style = AppTheme.typography.sourceType
        )

        Spacer(Modifier.width(20.dp))
    }

    Spacer(Modifier.height(12.dp))

    RenderSwitcher(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(8.dp)),
        title = stringResource(R.string.display_like_instead_of_previous_title),
        subtitle = stringResource(R.string.display_like_instead_of_previous_subtitle),
        value = displayLikeInsteadOfPrevious,
        enable = true,
        groupDivider = false,
        onChange = { newValue ->
            scope.launch(Dispatchers.IO) {
                appContext.settingsDataStore.edit { prefs ->
                    prefs[AppSettingKeys.DISPLAY_LIKE_INSTEAD_OF_PREVIOUS] = newValue
                }
                delay(350L)
                MediaSessionPipeline.get().refresh()
            }
        }
    )

    AnimatedVisibility(
        visible = displayLikeInsteadOfPrevious,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(300)
        ),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(300)
        ),
    ) {
        Column {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showLikeModeDialog = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        modifier = Modifier.padding(horizontal = 23.dp),
                        text = stringResource(R.string.like_button_action),
                        style = AppTheme.typography.screenTitle,
                        color = AppTheme.colors.contentPrimary
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = stringResource(R.string.like_icon_action_desc),
                        modifier = Modifier.padding(horizontal = 23.dp),
                        color = AppTheme.colors.contentPrimary.copy(alpha = 0.4f),
                        style = AppTheme.typography.dialogSubtitle
                    )
                }

                Text(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppTheme.colors.autoStart)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    text = likeModeOptionLabel(likeModeInt.toLikeMode),
                    color = AppTheme.colors.contentPrimary,
                    style = AppTheme.typography.sourceType
                )

                Spacer(Modifier.width(20.dp))
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    BaseButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 42.dp),
        title = stringResource(
            if (showBroadcastHint) {
                R.string.hide_broadcast_hint
            } else {
                R.string.show_broadcast_hint
            }
        ),
        containerColor = AppTheme.colors.surfaceMenu,
        onClick = { showBroadcastHint = !showBroadcastHint }
    )

    AnimatedVisibility(
        visible = showBroadcastHint,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(300)
        ),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(300)
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(32.dp))
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AppTheme.colors.contentPrimary.copy(alpha = 0.15f))
            )
            Spacer(Modifier.height(24.dp))

            SelectionContainer {
                Text(
                    text = stringResource(R.string.broadcast_intents_hint)
                        .spannedFromHtml()
                        .toAnnotatedString(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 42.dp),
                    color = AppTheme.colors.contentPrimary.copy(alpha = 0.8f),
                    style = AppTheme.typography.dialogSubtitle
                )
            }

            Spacer(Modifier.height(24.dp))
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AppTheme.colors.contentPrimary.copy(alpha = 0.15f))
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                activityContext.startActivity(
                    Intent(activityContext, LogActivity::class.java)
                )
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                modifier = Modifier.padding(horizontal = 23.dp),
                text = stringResource(R.string.logs),
                style = AppTheme.typography.screenTitle,
                color = AppTheme.colors.contentPrimary
            )

            Spacer(Modifier.height(5.dp))

            Text(
                text = stringResource(R.string.collect_debug_info),
                modifier = Modifier.padding(horizontal = 23.dp),
                color = AppTheme.colors.contentPrimary.copy(alpha = 0.4f),
                style = AppTheme.typography.dialogSubtitle
            )
        }
    }

    Spacer(Modifier.height(64.dp))
}

@Composable
private fun likeModeOptionLabel(mode: LikeMode): String = when (mode) {
    LikeMode.VENDOR_RATING -> stringResource(R.string.toggle_like)
    LikeMode.CURRENT_PLAYER -> stringResource(R.string.open_current_player)
    LikeMode.SEND_INTENT -> stringResource(R.string.send_intent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LikeModeDialog(
    initialLikeModeInt: Int,
    uiScaleState: Float,
    onDismiss: () -> Unit = {},
    onCancel: () -> Unit = { onDismiss() },
    onConfirmed: (LikeMode) -> Unit
) {
    val configuration = LocalConfiguration.current
    val dialogFillerSize =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) .2f else .1f
    val dialogHeightFiller =
        remember(configuration) { (configuration.screenHeightDp * dialogFillerSize).dp }

    BasicAlertDialog(
        modifier = Modifier
            .padding(
                bottom = dialogHeightFiller,
                top = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    4.dp
                } else dialogHeightFiller
            )
            .padding(horizontal = 24.dp),
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        val density = LocalDensity.current
        val scaledDensity = remember(density, uiScaleState) {
            Density(
                density.density * uiScaleState,
                density.fontScale * uiScaleState
            )
        }

        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Surface(
                modifier = Modifier,
                shape = RoundedCornerShape(8.dp),
                color = AppTheme.colors.surfaceBackground
            ) {
                Column(modifier = Modifier.padding(top = 22.dp)) {
                    Text(
                        text = stringResource(R.string.like_button_action),
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = AppTheme.colors.contentPrimary,
                        style = AppTheme.typography.dialogTitle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    var preSelected by remember(initialLikeModeInt) {
                        mutableStateOf(initialLikeModeInt.toLikeMode)
                    }
                    val listRadioColors = RadioButtonColors(
                        selectedColor = AppTheme.colors.contentPrimary.copy(.8f),
                        unselectedColor = AppTheme.colors.contentPrimary.copy(.3f),
                        disabledSelectedColor = AppTheme.colors.contentPrimary.copy(.3f),
                        disabledUnselectedColor = AppTheme.colors.contentPrimary.copy(.3f)
                    )

                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = .1f))
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, false)
                    ) {
                        item(key = -1) {
                            Spacer(
                                Modifier
                                    .height(.8.dp)
                            )
                        }
                        items(
                            items = LikeMode.entries,
                            key = { it.name }
                        ) { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { preSelected = mode }
                                    .padding(vertical = 6.dp)
                                    .padding(start = 24.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = preSelected == mode,
                                    onClick = { preSelected = mode },
                                    colors = listRadioColors
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = likeModeOptionLabel(mode),
                                    style = AppTheme.typography.dialogListTitle,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                    color = AppTheme.colors.contentPrimary
                                )
                            }
                        }
                    }

                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = .1f))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        Text(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onCancel)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            text = stringResource(android.R.string.cancel).uppercase(),
                            style = AppTheme.typography.dialogButton,
                            color = AppTheme.colors.contentAccent
                        )
                        Text(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    onConfirmed(preSelected)
                                    onCancel()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            text = stringResource(android.R.string.ok).uppercase(),
                            style = AppTheme.typography.dialogButton,
                            color = AppTheme.colors.contentAccent
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayAsAppSourceDialog(
    initialKey: String,
    uiScaleState: Float,
    onDismiss: () -> Unit = {},
    onCancel: () -> Unit = { onDismiss() },
    onConfirmed: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val dialogFillerSize =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) .2f else .1f
    val dialogHeightFiller =
        remember(configuration) { (configuration.screenHeightDp * dialogFillerSize).dp }

    BasicAlertDialog(
        modifier = Modifier
            .padding(
                bottom = dialogHeightFiller,
                top = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    4.dp
                } else dialogHeightFiller
            )
            .padding(horizontal = 24.dp),
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        val density = LocalDensity.current
        val scaledDensity = remember(density, uiScaleState) {
            Density(
                density.density * uiScaleState,
                density.fontScale * uiScaleState
            )
        }

        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Surface(
                modifier = Modifier,
                shape = RoundedCornerShape(8.dp),
                color = AppTheme.colors.surfaceBackground
            ) {
                Column(modifier = Modifier.padding(top = 22.dp)) {
                    Text(
                        text = stringResource(R.string.display_as_title),
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = AppTheme.colors.contentPrimary,
                        style = AppTheme.typography.dialogTitle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    var preSelectedKey by remember { mutableStateOf(initialKey) }
                    val listRadioColors = RadioButtonColors(
                        selectedColor = AppTheme.colors.contentPrimary.copy(.8f),
                        unselectedColor = AppTheme.colors.contentPrimary.copy(.3f),
                        disabledSelectedColor = AppTheme.colors.contentPrimary.copy(.3f),
                        disabledUnselectedColor = AppTheme.colors.contentPrimary.copy(.3f)
                    )

                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = .1f))
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, false)
                    ) {
                        item(key = -1) {
                            Spacer(
                                Modifier
                                    .height(.8.dp)
                            )
                        }
                        items(
                            items = DISPLAY_AS_APP_SOURCE_KEYS,
                            key = { it }
                        ) { key ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { preSelectedKey = key }
                                    .padding(vertical = 6.dp)
                                    .padding(start = 24.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DisplayAsAppSourceRowIcon(displayAsAppSourceDrawableRes(key))
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = displayAsAppSourceLabel(key),
                                    style = AppTheme.typography.dialogListTitle,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                    color = AppTheme.colors.contentPrimary
                                )
                                RadioButton(
                                    selected = preSelectedKey == key,
                                    onClick = { preSelectedKey = key },
                                    colors = listRadioColors
                                )
                            }
                        }
                    }

                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = .1f))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        Text(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onCancel)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            text = stringResource(android.R.string.cancel).uppercase(),
                            style = AppTheme.typography.dialogButton,
                            color = AppTheme.colors.contentAccent
                        )
                        Text(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    onConfirmed(preSelectedKey)
                                    onCancel()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            text = stringResource(android.R.string.ok).uppercase(),
                            style = AppTheme.typography.dialogButton,
                            color = AppTheme.colors.contentAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayAsAppSourceRowIcon(drawableRes: Int?) {
    val shape = RoundedCornerShape(4.dp)
    if (drawableRes != null) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = null,
            modifier = Modifier
                .size(26.dp)
                .clip(shape),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(shape)
                .background(AppTheme.colors.contentPrimary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "?",
                style = AppTheme.typography.dialogListTitle.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = AppTheme.colors.contentPrimary.copy(alpha = 0.45f)
            )
        }
    }
}
