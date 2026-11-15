package com.kooritea.fcmfix.ui.data

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.libxposed.service.XposedService

/** The options in LSPosed's remote preferences; system_server reads the same keys. */
data class ModuleConfig(
    val allowList: Set<String> = emptySet(),
    /** disableAutoCleanNotification */
    val keepNotifications: Boolean = false,
    /** includeIceBoxDisableApp */
    val iceBox: Boolean = false,
) {
    fun allows(packageName: String): Boolean = packageName in allowList

    fun withAllowed(packageName: String, allowed: Boolean): ModuleConfig = if (allowed) {
        copy(allowList = allowList + packageName)
    } else {
        copy(allowList = allowList - packageName)
    }
}

object ConfigStore {
    private const val PREFERENCES = "config"

    /** Binder call; throws when LSPosed cannot provide the preferences. */
    fun load(service: XposedService): ModuleConfig {
        val pref = service.getRemotePreferences(PREFERENCES)
        return ModuleConfig(
            allowList = pref.getStringSet("allowList", null).orEmpty().toSet(),
            keepNotifications = pref.getBoolean("disableAutoCleanNotification", false),
            iceBox = pref.getBoolean("includeIceBoxDisableApp", false),
        )
    }

    /**
     * Writes every key and tells the hooked processes to reload. Returns false when they cannot
     * be told (Android 13 and older) and the change needs a reboot; throws on a failed write.
     */
    fun save(context: Context, service: XposedService, config: ModuleConfig): Boolean {
        val saved = service.getRemotePreferences(PREFERENCES).edit()
            .putBoolean("init", true)
            .putStringSet("allowList", HashSet(config.allowList))
            .putBoolean("disableAutoCleanNotification", config.keepNotifications)
            .putBoolean("includeIceBoxDisableApp", config.iceBox)
            .commit()
        check(saved) { "配置写入失败" }
        if (Build.VERSION.SDK_INT < 34) return false
        context.sendBroadcast(
            Intent(context.packageName + ".update.config"), null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
        )
        return true
    }
}
