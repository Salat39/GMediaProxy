package com.geely.online.service.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class AppColors(
    val isDark: Boolean,

    val surfaceLayerAccentPale: Color,
    val surfaceBackground: Color,
    val surfaceLayer1: Color,
    val surfaceMenu: Color,
    val surfaceMenuDivider: Color,
    val surfaceSettings: Color,
    val surfaceSettingsLayer1: Color,
    val cardItemBackground: Color,
    val contentAccent: Color,
    val contentLightAccent: Color,
    val deleteButton: Color,
    val contentPrimary: Color,
    val contentWarning: Color,
    val addSplitTop: Color,
    val addSplitBottom: Color,
    val menuIcon: Color,
    val sliderPassive: Color,
    val autoStart: Color,
)
