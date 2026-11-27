package com.kooritea.fcmfix.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class KeepNotification extends XposedModule{

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
        try {
            this.recordNotificationPosts();
        } catch (Throwable e) {
            printLog("hook error notification posts: " + e.getMessage());
        }
    }

    /**
     * Counts non-foreground notification posts of allowlisted apps, so the app can pair them
     * with push records: "收到推送但没有发通知" then points at the app itself, not delivery.
     */
    protected void recordNotificationPosts() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader);
        Method targetMethod = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if ("enqueueNotificationInternal".equals(method.getName())
                    && method.getParameterTypes().length >= 8
                    && method.getParameterTypes()[0] == String.class) {
                // Prefer the widest overload: it carries the full AOSP parameter set.
                if (targetMethod == null
                        || method.getParameterTypes().length > targetMethod.getParameterTypes().length) {
                    targetMethod = method;
                }
            }
        }
        if (targetMethod == null) throw new NoSuchMethodError("enqueueNotificationInternal");
        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) return;
                if (!(param.args[0] instanceof String)) return;
                // Foreground-service notifications are not push notifications; skip them.
                Object last = param.args[param.args.length - 1];
                if (last instanceof Boolean && (Boolean) last) return;
                String pkg = (String) param.args[0];
                if (targetIsAllow(pkg)) {
                    SelfCheckCollector.recordNotification(pkg);
                }
            }
        });
        printLog("Notification post recording active: " + targetMethod.getName());
    }
    
    protected void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService",classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for(Method method : declareMethods){
            if("cancelAllNotificationsInt".equals(method.getName())){
                if(targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length){
                    targetMethod = method;
                }
            }
        }
        if(targetMethod != null){
            int pkg_args_index = 0;
            int reason_args_index = 0;
            if(Build.VERSION.SDK_INT == 30){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 31){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 32){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 33){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 34){
                if(targetMethod.getParameterTypes().length == 10){
                    pkg_args_index = 2;
                    reason_args_index = 8;
                }else if(targetMethod.getParameterTypes().length == 8){
                    pkg_args_index = 2;
                    reason_args_index = 7;
                }
            }
            if(Build.VERSION.SDK_INT == 35){
                pkg_args_index = 2;
                reason_args_index = 7;
            }
            if(Build.VERSION.SDK_INT > 35){
                pkg_args_index = 2;
                reason_args_index = 7;
            }
            if(pkg_args_index == 0 || reason_args_index == 0){
                throw new NoSuchMethodError();
            }
            // Validate the assumed positions like BroadcastFix verifies its own: on a different
            // overload shape this must fail the install (reported as a hook error, system keeps
            // its behavior), not throw ClassCastException/IOOBE inside the hooked method.
            Class<?>[] types = targetMethod.getParameterTypes();
            if(pkg_args_index >= types.length || types[pkg_args_index] != String.class
                    || reason_args_index >= types.length || types[reason_args_index] != int.class){
                throw new NoSuchMethodError("cancelAllNotificationsInt shape mismatch: pkg@"
                        + pkg_args_index + "=" + (pkg_args_index < types.length ? types[pkg_args_index].getName() : "missing")
                        + ", reason@" + reason_args_index + "="
                        + (reason_args_index < types.length ? types[reason_args_index].getName() : "missing"));
            }
            int finalPkg_args_index = pkg_args_index;
            int finalReason_args_index = reason_args_index;
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if(!isBootComplete){
                        return;
                    }
                    if(getBooleanConfig("disableAutoCleanNotification",false) && targetIsAllow((String) param.args[finalPkg_args_index])){
                        int reason = (int)param.args[finalReason_args_index];
                        if(reason == NotificationListenerService.REASON_PACKAGE_CHANGED){
                            param.setResult(null);
                        }
                        if(reason == 10020 || reason == 10021){ // cos15/oos15
                            param.setResult(null);
                        }
                    }
                }
            });
        }else{
            throw new NoSuchMethodError();
        }
    }
}
