package com.geely.online.service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.UserHandle

fun Context.launchApp(
    packageName: String,
    activityName: String? = null,
    user: UserHandle? = null
) = runCatching {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

    if (!activityName.isNullOrBlank()) {
        val fqcn = if (activityName.startsWith(".")) packageName + activityName else activityName
        val explicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, fqcn))
            .addFlags(flags)

        if (user != null && Build.VERSION.SDK_INT >= 24) {
            val la = getSystemService(LauncherApps::class.java)
            try {
                la?.startMainActivity(ComponentName(packageName, fqcn), user, Rect(), null)
                return@runCatching
            } catch (_: Exception) {
            }
        }

        try {
            startActivity(explicit)
            return@runCatching
        } catch (_: Exception) {
        }
    }

    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        launch.addFlags(flags)
        if (Build.VERSION.SDK_INT >= 24 && user != null) {
            val la = getSystemService(LauncherApps::class.java)
            val component = launch.component ?: return@let run {
                null
            }
            try {
                la?.startMainActivity(component, user, Rect(), null)
                return@runCatching
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            null
        }
        return@runCatching
    }

    val probe = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(packageName)

    val resolveInfo = if (Build.VERSION.SDK_INT >= 33) {
        val flagsResolve =
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        packageManager.queryIntentActivities(probe, flagsResolve).firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()
    } ?: return@runCatching

    val component =
        ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
    val launch = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(flags)

    if (Build.VERSION.SDK_INT >= 24 && user != null) {
        val la = getSystemService(LauncherApps::class.java)
        try {
            la?.startMainActivity(component, user, Rect(), null)
        } catch (_: Exception) {
            try {
                startActivity(launch)
            } catch (_: Exception) {
            }
        }
        return@runCatching
    }

    try {
        startActivity(launch)
    } catch (_: Exception) {
    }
}
