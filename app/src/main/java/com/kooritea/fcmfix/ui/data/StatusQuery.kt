package com.kooritea.fcmfix.ui.data

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.kooritea.fcmfix.util.HookStatus
import com.kooritea.fcmfix.util.SelfCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object StatusQuery {
    /** An ordered broadcast normally comes back within a second; a stuck receiver must not hang the UI. */
    private const val TIMEOUT_MS = 20_000L

    /**
     * Asks system_server for its hook status plus what extra selects:
     * SelfCheck.EXTRA_CHECK or HookStatus.EXTRA_LOGS. The answer is empty when no hooked
     * process answers, or on Android 13 and older.
     */
    suspend fun query(context: Context, extra: String = SelfCheck.EXTRA_CHECK): Bundle {
        if (Build.VERSION.SDK_INT < 34) return Bundle()
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                // The query must reach system_server's registered receiver while staying out of
                // reach of other apps: with an implicit ordered broadcast, any app could register
                // the same action and overwrite system_server's answer after it ran (same-priority
                // dynamic receivers run in registration order, so a later app's setResultExtras
                // wins and could fake a clean self-check). Receiver-permission gating is the fix
                // that works on every broadcast queue: deliverers without WRITE_SECURE_SETTINGS
                // (held by the system framework, unobtainable without adb) are skipped entirely,
                // so no third-party app even sees the query. Package-targeting with
                // setPackage("android") was tried here and cannot replace it: the modern broadcast
                // queue (Android 14+ emulators, OxygenOS 16) resolves targeted broadcasts only
                // against manifest receivers and finishes the call with zero dispatches.
                val query = Intent(context.packageName + HookStatus.QUERY_ACTION_SUFFIX)
                    .putExtra(extra, true)
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val extras = getResultExtras(false)
                        if (continuation.isActive) continuation.resume(extras ?: Bundle())
                    }
                }
                context.sendOrderedBroadcast(
                    query, "android.permission.WRITE_SECURE_SETTINGS", options, receiver,
                    Handler(Looper.getMainLooper()), 0, null, null,
                )
            }
        } ?: Bundle()
    }
}
