/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.assist;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;

import androidx.annotation.Nullable;

import com.android.systemui.Dependency;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;
import java.util.List;

/** Class to monitor and report the state of the phone. */
final class PhoneStateMonitor {

    private static final int PHONE_STATE_AOD1 = 1;
    private static final int PHONE_STATE_AOD2 = 2;
    private static final int PHONE_STATE_BOUNCER = 3;
    private static final int PHONE_STATE_UNLOCKED_LOCKSCREEN = 4;
    private static final int PHONE_STATE_HOME = 5;
    private static final int PHONE_STATE_OVERVIEW = 6;
    private static final int PHONE_STATE_ALL_APPS = 7;
    private static final int PHONE_STATE_APP_DEFAULT = 8;
    private static final int PHONE_STATE_APP_IMMERSIVE = 9;
    private static final int PHONE_STATE_APP_FULLSCREEN = 10;

    private static final String[] DEFAULT_HOME_CHANGE_ACTIONS = new String[] {
            PackageManagerWrapper.ACTION_PREFERRED_ACTIVITY_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED
    };

    private final Context mContext;
    private final StatusBarStateController mStatusBarStateController;

    private boolean mLauncherShowing;
    @Nullable private ComponentName mDefaultHome;

    PhoneStateMonitor(Context context) {
        mContext = context;
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);

        ActivityManagerWrapper activityManagerWrapper = ActivityManagerWrapper.getInstance();
        mDefaultHome = getCurrentDefaultHome();
        IntentFilter intentFilter = new IntentFilter();
        for (String action : DEFAULT_HOME_CHANGE_ACTIONS) {
            intentFilter.addAction(action);
        }
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mDefaultHome = getCurrentDefaultHome();
            }
        }, intentFilter);
        mLauncherShowing = isLauncherShowing(activityManagerWrapper.getRunningTask());
        activityManagerWrapper.registerTaskStackListener(new TaskStackChangeListener() {
            @Override
            public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                mLauncherShowing = isLauncherShowing(taskInfo);
            }
        });
    }

    int getPhoneState() {
        int phoneState;
        if (isShadeFullscreen()) {
            phoneState = getPhoneLockscreenState();
        } else if (mLauncherShowing) {
            phoneState = getPhoneLauncherState();
        } else {
            phoneState = getPhoneAppState();
        }
        return phoneState;
    }

    @Nullable
    private static ComponentName getCurrentDefaultHome() {
        List<ResolveInfo> homeActivities = new ArrayList<>();
        ComponentName defaultHome =
                PackageManagerWrapper.getInstance().getHomeActivities(homeActivities);
        if (defaultHome != null) {
            return defaultHome;
        }

        int topPriority = Integer.MIN_VALUE;
        ComponentName topComponent = null;
        for (ResolveInfo resolveInfo : homeActivities) {
            if (resolveInfo.priority > topPriority) {
                topComponent = resolveInfo.activityInfo.getComponentName();
                topPriority = resolveInfo.priority;
            } else if (resolveInfo.priority == topPriority) {
                topComponent = null;
            }
        }
        return topComponent;
    }

    private int getPhoneLockscreenState() {
        if (isDozing()) {
            return PHONE_STATE_AOD1;
        } else if (isBouncerShowing()) {
            return PHONE_STATE_BOUNCER;
        } else if (isKeyguardLocked()) {
            return PHONE_STATE_AOD2;
        } else {
            return PHONE_STATE_UNLOCKED_LOCKSCREEN;
        }
    }

    private int getPhoneLauncherState() {
        if (isLauncherInOverview()) {
            return PHONE_STATE_OVERVIEW;
        } else if (isLauncherInAllApps()) {
            return PHONE_STATE_ALL_APPS;
        } else {
            return PHONE_STATE_HOME;
        }
    }

    private int getPhoneAppState() {
        if (isAppImmersive()) {
            return PHONE_STATE_APP_IMMERSIVE;
        } else if (isAppFullscreen()) {
            return PHONE_STATE_APP_FULLSCREEN;
        } else {
            return PHONE_STATE_APP_DEFAULT;
        }
    }

    private boolean isShadeFullscreen() {
        int statusBarState = mStatusBarStateController.getState();
        return statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED;
    }

    private boolean isDozing() {
        return mStatusBarStateController.isDozing();
    }

    private boolean isLauncherShowing(ActivityManager.RunningTaskInfo runningTaskInfo) {
        if (runningTaskInfo == null) {
            return false;
        } else {
            return runningTaskInfo.topActivity.equals(mDefaultHome);
        }
    }

    private boolean isAppImmersive() {
        return SysUiServiceProvider.getComponent(mContext, StatusBar.class).inImmersiveMode();
    }

    private boolean isAppFullscreen() {
        return SysUiServiceProvider.getComponent(mContext, StatusBar.class).inFullscreenMode();
    }

    private boolean isBouncerShowing() {
        StatusBar statusBar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
        return statusBar != null && statusBar.isBouncerShowing();
    }

    private boolean isKeyguardLocked() {
        // TODO: Move binder call off of critical path
        KeyguardManager keyguardManager = mContext.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    private boolean isLauncherInOverview() {
        // TODO
        return false;
    }

    private boolean isLauncherInAllApps() {
        // TODO
        return false;
    }
}
