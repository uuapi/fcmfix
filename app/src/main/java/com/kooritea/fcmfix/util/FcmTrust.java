package com.kooritea.fcmfix.util;

/**
 * Sender and action checks for the delivery trust model. Trust is derived per invocation from
 * the framework's own records (BroadcastRecord / known parameter signatures), never from
 * Intent extras or ambient per-thread state.
 */
public final class FcmTrust {
    public static final String RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    public static final String TASK_READY = "com.google.android.gms.gcm.ACTION_TASK_READY";

    private FcmTrust() {}

    public static boolean isGmsSender(int uid, String[] packages) {
        if (uid < 10000 || packages == null) return false;
        for (String name : packages) if ("com.google.android.gms".equals(name)) return true;
        return false;
    }

    /**
     * Auto-allow and the "FCM" badge skip system-uid packages such as "android": they declare
     * FCM receivers but are never frozen and get no pushes of their own.
     */
    public static boolean isAppUid(int uid) {
        return uid % 100000 >= 10000;
    }

    public static boolean isAction(String action) {
        return RECEIVE.equals(action) || "com.google.firebase.MESSAGING_EVENT".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action);
    }

    public static boolean allowsService(String action, boolean gms, boolean selfInWindow,
                                        boolean gcmBind) {
        if (TASK_READY.equals(action)) return gcmBind && gms;
        if (!isAction(action)) return false;
        return gms || (!RECEIVE.equals(action) && selfInWindow);
    }

    public static String target(String packageName, String componentPackage) {
        if (componentPackage != null && packageName != null && !componentPackage.equals(packageName)) {
            return null;
        }
        String target = componentPackage == null ? packageName : componentPackage;
        return target == null || target.isEmpty() ? null : target;
    }
}
