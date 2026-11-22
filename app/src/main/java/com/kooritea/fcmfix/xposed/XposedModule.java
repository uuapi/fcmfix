package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.DiagnosticLogger;
import com.kooritea.fcmfix.util.FcmTrust;
import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.OplusAttribution;
import com.kooritea.fcmfix.util.SelfCheck;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    private static String selfPackageName = "UNKNOWN";

    protected final ClassLoader classLoader;
    static final String TAG = "FcmFix";
    private static volatile ConfigSnapshot config;

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static Boolean isBootComplete = false;
    private static Thread loadConfigThread = null;
    private static final Set<String> loggedWarnings = ConcurrentHashMap.newKeySet();
    protected static final String MODULE_PACKAGE_NAME = "com.kooritea.fcmfix";
    private static final String ACTION_QUERY_STATUS = MODULE_PACKAGE_NAME + HookStatus.QUERY_ACTION_SUFFIX;
    private static final String ACTION_ENABLE_NOTIFY = MODULE_PACKAGE_NAME + ".enable.notify";
    private static final DiagnosticLogger diagnosticLogger = new DiagnosticLogger(
            text -> XposedBridge.log("[fcmfix] " + text),
            error -> Log.w(TAG, "Diagnostic logging failed; further warnings suppressed", error));

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    private static void initContext(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if (context == null) {
                    context = (Context) methodHookParam.thisObject;
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    } else {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    private static void callAllOnCanReadConfig() {
        initReceiver();
        if ("android".equals(getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(60000);
                    isBootComplete = true;
                    printLog("Boot Complete");
                } catch (Throwable e) {
                    printLog(e.getMessage());
                }
            }).start();
        } else {
            isBootComplete = true;
        }
        for (XposedModule instance : instances) {
            try {
                instance.onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable {
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        diagnosticLogger.log("[" + getSelfPackageName() + "]" + text, isDiagnosticsLog,
                SystemClock.elapsedRealtime());
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Throwable e) {
                printLog("更新配置文件失败: " + e.getMessage());
            }
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName) {
        ConfigSnapshot snapshot = config;
        if (snapshot == null) {
            checkUserDeviceUnlockAndUpdateConfig();
            return false;
        }
        return snapshot.allows(packageName, FcmPackages.current());
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        ConfigSnapshot snapshot = config;
        if (snapshot == null) {
            checkUserDeviceUnlockAndUpdateConfig();
            return defaultValue;
        }
        return snapshot.options.getOrDefault(key, defaultValue);
    }

    protected static void onUpdateConfig() {
        if (loadConfigThread == null) {
            loadConfigThread = new Thread() {
                @Override
                public void run() {
                    super.run();
                    try {
                        SharedPreferences remotePreferences = XposedBridge.getRemotePreferences("config");
                        if (remotePreferences == null) {
                            throw new IllegalStateException("remotePreferences 不可用");
                        }
                        ConfigSnapshot loaded = new ConfigSnapshot(remotePreferences.getAll());
                        config = loaded;
                        // Every hooked process consults targetIsAllow; scan in each of them, not
                        // only system_server, so auto-allow also works in scoped app processes.
                        if (loaded.autoAllowFcm()) {
                            FcmPackages.ensureScanned(context);
                        }
                        if ("android".equals(getSelfPackageName())) {
                            printLog("配置已加载：手动放行 " + loaded.allowList.size()
                                    + " 个，自动放行 " + loaded.autoAllowFcm());
                        }
                    } catch (Throwable e) {
                        printLog("通过现代Xposed API读取配置失败: " + e.getMessage());
                    }
                    loadConfigThread = null;
                }
            };
            loadConfigThread.start();
        }
    }

    private static void onUninstallFcmfix() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmfix");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            if ("android".equals(getSelfPackageName())) {
                FcmConnectionMonitor.start(context);
            }

            // Protected system broadcasts; no export flag needed. Kept current in every hooked
            // process so each one's FcmPackages set tracks installs and removals.
            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent.getData() != null) {
                        FcmPackages.onPackageChanged(receiverContext, intent.getData().getSchemeSpecificPart());
                    }
                }
            }, packageFilter);

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction("com.kooritea.fcmfix.update.config");
            if (Build.VERSION.SDK_INT >= 34) {
                updateConfigIntentFilter.addAction(ACTION_QUERY_STATUS);
                updateConfigIntentFilter.addAction(ACTION_ENABLE_NOTIFY);
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context receiverContext, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action) && isConfigSender(this)) {
                            onUpdateConfig();
                        } else if (ACTION_ENABLE_NOTIFY.equals(action) && isConfigSender(this)) {
                            String pkg = intent.getStringExtra("pkg");
                            if (pkg != null && !"android".equals(getSelfPackageName())) return;
                            if (pkg != null) {
                                new Thread(() -> enableNotifications(context, pkg), "FCMFix-enable-notify").start();
                            }
                        } else if (ACTION_QUERY_STATUS.equals(action) && isOrderedBroadcast() && isConfigSender(this)) {
                            if (intent.getBooleanExtra(SelfCheck.EXTRA_CHECK, false)
                                    && "android".equals(getSelfPackageName())) {
                                answerSelfCheck(goAsync());
                            } else {
                                setResultExtras(addHookStatus(getResultExtras(true),
                                        intent.getBooleanExtra(HookStatus.EXTRA_LOGS, false)));
                            }
                        }
                    }
                }, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                logOnce("Android 14 以下不支持带身份校验的即时刷新和状态查询；配置修改后需重启生效。");
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && "com.kooritea.fcmfix".equals(intent.getData().getSchemeSpecificPart())) {
                        Bundle extras = intent.getExtras();
                        if (extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                            return;
                        }
                        onUninstallFcmfix();
                        if ("android".equals(getSelfPackageName())) {
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    private static Bundle addHookStatus(Bundle result, boolean logs) {
        String process = getSelfPackageName();
        if (logs) result.putByteArray(process + HookStatus.LOGS_SUFFIX, HookStatus.pack(XposedBridge.recentLogs()));
        result.putStringArrayList(process + HookStatus.ACTIVE_SUFFIX, OplusHooks.STATUS.active());
        result.putStringArrayList(process + HookStatus.FAILED_SUFFIX, OplusHooks.STATUS.failed());
        result.putStringArrayList(process + HookStatus.SKIPPED_SUFFIX, OplusHooks.STATUS.skipped());
        return result;
    }

    /** Self-check reads other services over Binder; keep it off system_server's main thread. */
    private static void answerSelfCheck(BroadcastReceiver.PendingResult pending) {
        new Thread(() -> {
            try {
                Bundle extras = addHookStatus(pending.getResultExtras(true), false);
                try {
                    SelfCheckCollector.collect(context, config, extras);
                } catch (Throwable e) {
                    printLog("Self-check failed: " + e);
                }
                pending.setResultExtras(extras);
            } finally {
                pending.finish();
            }
        }, "FCMFix-selfcheck").start();
    }

    private static boolean isConfigSender(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34 || context == null) return false;
        try {
            return MODULE_PACKAGE_NAME.equals(receiver.getSentFromPackage())
                    && receiver.getSentFromUid() == context.getPackageManager().getPackageUid(MODULE_PACKAGE_NAME, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The module app may turn an allowed app's notifications on, never off, and only through a
     * sender-checked broadcast: notification state is the app's own choice otherwise.
     */
    private static void enableNotifications(Context context, String packageName) {
        try {
            ConfigSnapshot snapshot = config;
            if (snapshot == null || !snapshot.allows(packageName, FcmPackages.current())) {
                printLog("Enable notifications skipped, not allowlisted: " + packageName);
                return;
            }
            android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            if (!FcmTrust.isAppUid(info.uid)) return;
            Object service = NotificationManager.class.getMethod("getService").invoke(null);
            Class.forName("android.app.INotificationManager")
                    .getMethod("setNotificationsEnabledForPackage", String.class, int.class, boolean.class)
                    .invoke(service, packageName, info.uid, Boolean.TRUE);
            printLog("Enabled notifications: " + packageName, true);
        } catch (Throwable e) {
            printLog("Enable notifications failed for " + packageName + ": " + e);
        }
    }

    protected void sendNotification(String title) {
        sendNotification(title, null, null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title, content, null);
    }

    @SuppressLint("MissingPermission")
    protected void sendNotification(String title, String content, PendingIntent pendingIntent) {
        printLog(title, false);
        title = "[fcmfix]" + title;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createFcmfixChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmfix") == null) {
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
                "com.google.firebase.MESSAGING_EVENT".equals(action) ||
                "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static void logOnce(String message) {
        // Bounded deduplication so third-party spam cannot grow system_server memory indefinitely.
        if (loggedWarnings.size() < 64 && loggedWarnings.add(message)) printLog(message);
    }

    protected String findAllowedPackageArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && targetIsAllow((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    protected static boolean isGmsUid(int uid) {
        if (context == null || uid < 10000) return false;
        // Existing unfreeze/UID resolution uses this process's user. Do not apply its
        // window to the personal-profile copy of a work-profile broadcast target.
        if (!android.os.UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle())) return false;
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return FcmTrust.isGmsSender(uid, packages);
        } catch (RuntimeException e) {
            printLog("Cannot verify GMS UID: " + e.getMessage());
        }
        return false;
    }

    protected static String explicitTarget(Intent intent) {
        if (intent == null) return null;
        return FcmTrust.target(intent.getPackage(), intent.getComponent() == null
                ? null : intent.getComponent().getPackageName());
    }

    protected static int attributionIndex(Method method) {
        String[] types = java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new);
        return OplusAttribution.callerIndex(method.getDeclaringClass().getName(),
                method.getName(), method.getReturnType().getName(), types);
    }

    /**
     * The ColorOS-fork delivery trust model: a gate is only bypassed when the invocation
     * carries a real GMS sender, an exact FCM action and an allowlisted explicit target.
     */
    protected boolean trustedDelivery(Intent intent, String target, XC_MethodHook.MethodHookParam param) {
        // Require a coherent, explicit destination. Do not select an arbitrary allowlisted string argument.
        if (!isBootComplete || intent == null || target == null || !target.equals(explicitTarget(intent))
                || !targetIsAllow(target)) return false;
        try {
            // A queued broadcast is attributed by BroadcastRecord, never by ambient Binder identity.
            for (Object arg : param.args) {
                if (arg == null || !"com.android.server.am.BroadcastRecord".equals(arg.getClass().getName())) continue;
                return XposedHelpers.getObjectField(arg, "intent") == intent
                        && FcmTrust.RECEIVE.equals(intent.getAction())
                        && isGmsUid((Integer) XposedHelpers.getObjectField(arg, "callingUid"));
            }
            if (param.method instanceof Method) {
                Method method = (Method) param.method;
                int index = attributionIndex(method);
                if (index >= 0) {
                    int callerUid = (Integer) param.args[index];
                    String methodName = method.getName();
                    // Validate the actual callee too, not only the Intent destination.
                    if ("isAppClassifyRestricted".equals(methodName)) {
                        if (!target.equals(param.args[1])) return false;
                    } else {
                        int serviceIndex = "isAllowStartFromBindService".equals(methodName) ? 3 : 4;
                        Object info = XposedHelpers.getObjectField(param.args[serviceIndex], "appInfo");
                        if (!(info instanceof android.content.pm.ApplicationInfo)
                                || !target.equals(((android.content.pm.ApplicationInfo) info).packageName)) return false;
                    }
                    boolean gms = isGmsUid(callerUid);
                    String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
                    boolean selfInWindow = packages != null && java.util.Arrays.asList(packages).contains(target)
                            && OplusProxyFix.isInFcmDeliveryWindow(callerUid);
                    boolean gcmBind = "isAllowStartFromBindService".equals(methodName)
                            && "bsgcm".equals(param.args[5]);
                    boolean trusted = FcmTrust.allowsService(intent.getAction(), gms, selfInWindow, gcmBind);
                    if (trusted && gms) OplusProxyFix.beginFcmDeliveryWindow(target);
                    if (!trusted) logOnce("FCM rejected: untrusted service sender uid=" + callerUid
                            + ", target=" + target + ", method=" + methodName);
                    return trusted;
                }
            }
            // No BroadcastRecord argument and no known attribution signature: fail closed.
            // (This line is only reachable for methods hooked without either verification path.)
            logOnce("Unverified delivery attribution, refusing bypass: " + param.method);
            return false;
        } catch (Throwable error) {
            logOnce("Unsupported delivery attribution: " + param.method + ": " + error);
            return false;
        }
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
