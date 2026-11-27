package com.kooritea.fcmfix;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.MiuiLocalNotificationFix;
import com.kooritea.fcmfix.xposed.OplusProxyFix;
import com.kooritea.fcmfix.xposed.OplusStartupFix;
import com.kooritea.fcmfix.xposed.PowerkeeperFix;
import com.kooritea.fcmfix.xposed.ReconnectManagerFix;
import com.kooritea.fcmfix.xposed.XposedModule;

import io.github.libxposed.api.XposedModuleInterface;

public class XposedMain extends io.github.libxposed.api.XposedModule {

    /**
     * API 102 entry: the framework instantiates this class without arguments and calls its own
     * two-argument attachFramework on the instance.
     */
    public XposedMain() {
    }

    /**
     * API 101 entry: the framework's loader reflects on a two-argument constructor with
     * ModuleLoadedParam. Without it, module.prop's minApiVersion claim is false and frameworks
     * implementing that convention fail to load the module with a NoSuchMethodError. The
     * XposedInterface is attached reflectively because the wrapper's attach signature changed
     * between API 101 (one argument) and API 102 (two arguments); this constructor only runs on
     * pre-102 frameworks, and an API 100 framework (no attach method at all) stays unsupported,
     * which is what minApiVersion=101 declares.
     */
    public XposedMain(io.github.libxposed.api.XposedInterface base,
                      XposedModuleInterface.ModuleLoadedParam param) {
        Runnable detach = () -> { };
        try {
            getClass().getMethod("attachFramework", io.github.libxposed.api.XposedInterface.class,
                    Runnable.class).invoke(this, base, detach);
        } catch (Throwable e) {
            try {
                getClass().getMethod("attachFramework", io.github.libxposed.api.XposedInterface.class)
                        .invoke(this, base);
            } catch (Throwable attachError) {
                android.util.Log.e("fcmfix", "cannot attach framework api classes", attachError);
            }
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();
        XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/com.android.server.am.BroadcastController");
        new BroadcastFix(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.notification.NotificationManagerServiceInjector");
        new MiuiLocalNotificationFix(classLoader);

        XposedBridge.log("[fcmfix] com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        new AutoStartFix(classLoader);

        XposedBridge.log("[fcmfix] start hook OplusAppStartupManager startup gates");
        new OplusStartupFix(classLoader);

        XposedBridge.log("[fcmfix] com.android.server.notification.NotificationManagerService");
        new KeepNotification(classLoader);

        XposedBridge.log("[fcmfix] start hook com.android.server.power.OplusProxyWakeLock");
        new OplusProxyFix(classLoader);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        if ("com.google.android.gms".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.google.android.gms");
            XposedBridge.log("[fcmfix] start hook com.google.android.gms");
            new ReconnectManagerFix(param.getClassLoader());
        }

        if ("com.miui.powerkeeper".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.miui.powerkeeper");
            XposedBridge.log("[fcmfix] start hook com.miui.powerkeeper");
            new PowerkeeperFix(param.getClassLoader());
        }
    }
}
