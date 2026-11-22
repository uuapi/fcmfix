package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.XposedUtils;

public class BroadcastFix extends XposedModule {

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookBroadcastIntentLocked();
        }catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
    }

    protected void startHookBroadcastIntentLocked(){
        Method targetMethod = null;
        int intent_args_index = 0;
        int appOp_args_index = 0;
        if(Build.VERSION.SDK_INT >= 35){
            // API 35+ moves the entry point to BroadcastController on the tested OxygenOS 16
            // build, with intent at 3 and appOp at 13. Verify the assumed positions and fall
            // back to the AMS search below on any other firmware instead of giving up.
            Method controller = XposedUtils.tryFindMethodMostParam(classLoader,"com.android.server.am.BroadcastController","broadcastIntentLocked");
            if(controller != null
                    && controller.getParameterTypes().length > 13
                    && controller.getParameterTypes()[3] == Intent.class
                    && controller.getParameterTypes()[13] == int.class){
                targetMethod = controller;
                intent_args_index = 3;
                appOp_args_index = 13;
            }
        }
        if(targetMethod == null){
            targetMethod = XposedUtils.tryFindMethodMostParam(classLoader,"com.android.server.am.ActivityManagerService","broadcastIntentLocked");
            if(targetMethod != null){
                Parameter[] parameters = targetMethod.getParameters();
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q){
                    intent_args_index = 2;
                    appOp_args_index = 9;
                }else if(Build.VERSION.SDK_INT == Build.VERSION_CODES.R){
                    intent_args_index = 3;
                    appOp_args_index = 10;
                }else if(Build.VERSION.SDK_INT == 31){
                    intent_args_index = 3;
                    if(parameters[11].getType() == int.class){
                        appOp_args_index = 11;
                    }
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                }else if(Build.VERSION.SDK_INT == 32){
                    intent_args_index = 3;
                    if(parameters[11].getType() == int.class){
                        appOp_args_index = 11;
                    }
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                }else if(Build.VERSION.SDK_INT == 33){
                    intent_args_index = 3;
                    appOp_args_index = 12;
                } else if(Build.VERSION.SDK_INT == 34){
                    intent_args_index = 3;
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                    if(parameters[13].getType() == int.class){
                        appOp_args_index = 13;
                    }
                } else { // API >= 35: Q, R, 31-34 were handled above.
                    intent_args_index = 3;
                    if(parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                    if(parameters[13].getType() == int.class){
                        appOp_args_index = 13;
                    }
                }
                if(intent_args_index == 0 || appOp_args_index == 0){
                    intent_args_index = 0;
                    appOp_args_index = 0;
                    // 根据参数名称查找，部分经过混淆的系统无效
                    for(int i = 0; i < parameters.length; i++){
                        if("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class){
                            appOp_args_index = i;
                        }
                        if("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class){
                            intent_args_index = i;
                        }
                    }
                }
            }
        }
        if(targetMethod != null && intent_args_index != 0 & appOp_args_index != 0 && targetMethod.getParameters()[intent_args_index].getType() == Intent.class && targetMethod.getParameters()[appOp_args_index].getType() == int.class){
            createBroadcastIntentLockedHooker(intent_args_index,appOp_args_index,targetMethod);
        } else {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
        }
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method){
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.getDeclaringClass().getName());
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;

        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[finalIntent_args_index] == null){
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                // 介入条件：Intent未包含唤醒停止的pkg 且 Intent是FCM
                if((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0 && isFCMIntent(intent)){
                    String target;
                    if (intent.getComponent() != null) {
                        target = intent.getComponent().getPackageName();
                    } else {
                        target = intent.getPackage();
                    }
                    if(targetIsAllow(target)){
                        int i = (Integer) methodHookParam.args[finalAppOp_args_index];
                        if (i == -1) {
                            methodHookParam.args[finalAppOp_args_index] = 11;
                        }
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        printLog("Send Forced Start Broadcast: " + target, true);
                        // cos15 unfreeze
                        OplusProxyFix.unfreeze(target);
                        SelfCheckCollector.recordPush(target);
                    }
                }
            }
        });
    }

}
