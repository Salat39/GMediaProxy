package com.geely.online.service.ui.theme

import androidx.compose.ui.graphics.Color

internal val LightAppColors = AppColors(
    isDark = false,

    surfaceLayerAccentPale = ColorPalette.BrandBlue400.copy(alpha = 0.3f),
    surfaceBackground = Color(0xFFf7f8fa),
    surfaceLayer1 = Color(0xFF314e70),
    surfaceMenu = Color(0xFF4a76a8),
    surfaceMenuDivider = Color(0xFF4a76a8),
    surfaceSettings = Color(0xFFeceff3),
    surfaceSettingsLayer1 = Color(0xFFeceff3),
    cardItemBackground = Color(0xFF212121),
    contentAccent = Color(0xFF0099FA),
    contentLightAccent = Color(0xFF0099FA),
    deleteButton = Color(0xFFF44336),
    contentPrimary = Color(0xFF314e70),
    contentWarning = ColorPalette.Yellow700,
    addSplitTop = Color(0xFF1565C0),
    addSplitBottom = Color(0xFF00695C),
    menuIcon = ColorPalette.BaseWhite,
    sliderPassive = Color(0xFF546E7A),
    autoStart = Color(0xFF546E7A),
)

internal val DarkAppColors = AppColors(
    isDark = true,

    surfaceLayerAccentPale = ColorPalette.BrandBlue800.copy(alpha = 0.3f),
    surfaceBackground = Color(0xFF1f1f1f),
    surfaceLayer1 = Color(0xFF121212),
    surfaceMenu = Color(0xFF2D2D2D),
    surfaceMenuDivider = Color(0xFF232323),
    surfaceSettings = Color(0xFF1A1A1A),
    surfaceSettingsLayer1 = Color(0xFF262626),
    cardItemBackground = Color(0xFF1e1e1e),
    contentAccent = Color(0xFF1975d0),
    contentLightAccent = Color(0xFF1975d0),
    deleteButton = Color(0xFFF44336),
    contentPrimary = Color(0xFFe5e5e5),
    contentWarning = ColorPalette.Yellow500,
    addSplitTop = Color(0xFF2E7D32),
    addSplitBottom = Color(0xFF283593),
    menuIcon = ColorPalette.BaseWhite,
    sliderPassive = Color(0xFF626262),
    autoStart = Color(0xFF01579B),
)
