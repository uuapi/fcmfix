package com.kooritea.fcmfix.ui.data

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kooritea.fcmfix.util.ConfigSnapshot
import io.github.libxposed.service.XposedService

/** The options in LSPosed's remote preferences; system_server reads them through ConfigSnapshot. */
data class ModuleConfig(
    val allowList: Set<String> = emptySet(),
    /** FCM apps unticked while auto-allow is on. */
    val excludeList: Set<String> = emptySet(),
    val autoAllowFcm: Boolean = false,
    /** App-side only: enable notifications for auto-allowed apps that keep them off. */
    val autoEnableNotify: Boolean = false,
    /** disableAutoCleanNotification */
    val keepNotifications: Boolean = false,
) {
    /** Same rule as ConfigSnapshot.allows in system_server. */
    fun allows(packageName: String, includeFcm: Boolean): Boolean =
        packageName in allowList || (autoAllowFcm && includeFcm && packageName !in excludeList)

    /** Allowed by the auto-allow switch rather than ticked by hand. */
    fun allowsAutomatically(packageName: String, includeFcm: Boolean): Boolean =
        packageName !in allowList && allows(packageName, includeFcm)

    /**
     * With auto-allow on, an FCM app is ticked by leaving the exclude list and unticked by
     * joining it; other apps use the manual allow list either way.
     */
    fun withAllowed(packageName: String, includeFcm: Boolean, allowed: Boolean): ModuleConfig {
        val automatic = autoAllowFcm && includeFcm
        return if (allowed) {
            copy(
                excludeList = excludeList - packageName,
                allowList = if (automatic) allowList else allowList + packageName,
            )
        } else {
            copy(
                allowList = allowList - packageName,
                excludeList = if (automatic) excludeList + packageName else excludeList,
            )
        }
    }
}

object ConfigStore {
    private const val PREFERENCES = "config"

    /** Binder call; throws when LSPosed cannot provide the preferences. */
    fun load(service: XposedService): ModuleConfig {
        val pref = service.getRemotePreferences(PREFERENCES)
        return ModuleConfig(
            allowList = pref.getStringSet("allowList", null).orEmpty().toSet(),
            excludeList = pref.getStringSet("excludeList", null).orEmpty().toSet(),
            autoAllowFcm = pref.getBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, false),
            autoEnableNotify = pref.getBoolean("autoEnableNotify", false),
            keepNotifications = pref.getBoolean("disableAutoCleanNotification", false),
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
            .putStringSet("excludeList", HashSet(config.excludeList))
            .putBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, config.autoAllowFcm)
            .putBoolean("autoEnableNotify", config.autoEnableNotify)
            .putBoolean("disableAutoCleanNotification", config.keepNotifications)
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
