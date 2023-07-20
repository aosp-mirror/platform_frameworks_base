package com.android.internal.util.xtended;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import android.provider.Settings;

public class HideDeveloperStatusUtils {
    private static Set<String> mApps = new HashSet<>();
    private static final Set<String> settingsToHide = new HashSet<>(Arrays.asList(
        Settings.Global.ADB_ENABLED,
        Settings.Global.ADB_WIFI_ENABLED,
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    ));

    public static boolean shouldHideDevStatus(Context mContext, String packageName, String name) {
        return getApps(mContext).contains(packageName) && settingsToHide.contains(name);
    }

    public static Set<String> getApps(Context mContext) {
        String apps = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS);
        if (apps != null) {
            mApps = new HashSet<>(Arrays.asList(apps.split(",")));
        } else {
            mApps = new HashSet<>();
        }
        return mApps;
    }

    public void addApp(Context mContext, String packageName) {
        mApps.add(packageName);
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS, String.join(",", mApps));
    }

    public void removeApp(Context mContext, String packageName) {
        mApps.remove(packageName);
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS, String.join(",", mApps));
    }

    public void setApps(Context mContext) {
        String apps = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS,
                UserHandle.USER_SYSTEM);
        if (apps != null) {
            mApps = new HashSet<>(Arrays.asList(apps.split(",")));
        } else {
            mApps = new HashSet<>();
        }
    }
}
