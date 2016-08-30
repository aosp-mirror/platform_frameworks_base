package com.android.internal.util.rastapop;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.List;

public class DevUtils {

    /**
     * Kills the top most / most recent user application, but leaves out the launcher.
     * This is function governed by {@link Settings.Secure.KILL_APP_LONGPRESS_BACK}.
     *
     * @param context the current context, used to retrieve the package manager.
     * @return {@code true} when a user application was found and closed.
     */
    public static boolean killForegroundApplication(Context context) {
        boolean targetKilled = false;
        try {
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            String defaultHomePackage = "com.android.launcher";
            intent.addCategory(Intent.CATEGORY_HOME);
            final ResolveInfo res = context.getPackageManager().resolveActivity(intent, 0);
            if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
                defaultHomePackage = res.activityInfo.packageName;
            }
            IActivityManager am = ActivityManagerNative.getDefault();
            List<RunningAppProcessInfo> apps = am.getRunningAppProcesses();
            for (RunningAppProcessInfo appInfo : apps) {
                int uid = appInfo.uid;
                // Make sure it's a foreground user application (not system,
                // root, phone, etc.)
                if (uid >= Process.FIRST_APPLICATION_UID && uid <= Process.LAST_APPLICATION_UID
                        && appInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    if (appInfo.pkgList != null && (appInfo.pkgList.length > 0)) {
                        for (String pkg : appInfo.pkgList) {
                            if (!pkg.equals("com.android.systemui") && !pkg.equals(defaultHomePackage)) {
                                am.forceStopPackage(pkg, UserHandle.USER_CURRENT);
                                targetKilled = true;
                                break;
                            }
                        }
                    } else {
                        Process.killProcess(appInfo.pid);
                        targetKilled = true;
                        break;
                    }
                }
            }
        } catch (RemoteException remoteException) {
            // Do nothing; just let it go.
        }
        return targetKilled;
    }
}
