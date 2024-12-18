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

import com.android.systemui.BootCompleteCache;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import dagger.Lazy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

/** Class to monitor and report the state of the phone. */
@SysUISingleton
public final class PhoneStateMonitor {

    public static final int PHONE_STATE_AOD1 = 1;
    public static final int PHONE_STATE_AOD2 = 2;
    public static final int PHONE_STATE_BOUNCER = 3;
    public static final int PHONE_STATE_UNLOCKED_LOCKSCREEN = 4;
    public static final int PHONE_STATE_HOME = 5;
    public static final int PHONE_STATE_OVERVIEW = 6;
    public static final int PHONE_STATE_ALL_APPS = 7;
    public static final int PHONE_STATE_APP_DEFAULT = 8;
    public static final int PHONE_STATE_APP_IMMERSIVE = 9;
    public static final int PHONE_STATE_APP_FULLSCREEN = 10;

    private static final String[] DEFAULT_HOME_CHANGE_ACTIONS = new String[] {
            PackageManagerWrapper.ACTION_PREFERRED_ACTIVITY_CHANGED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED
    };

    private final Context mContext;
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private final StatusBarStateController mStatusBarStateController;

    private boolean mLauncherShowing;
    @Nullable private ComponentName mDefaultHome;

    @Inject
    PhoneStateMonitor(Context context, BroadcastDispatcher broadcastDispatcher,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            BootCompleteCache bootCompleteCache,
            StatusBarStateController statusBarStateController) {
        mContext = context;
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mStatusBarStateController = statusBarStateController;

        mDefaultHome = getCurrentDefaultHome();
        bootCompleteCache.addListener(() -> mDefaultHome = getCurrentDefaultHome());
        IntentFilter intentFilter = new IntentFilter();
        for (String action : DEFAULT_HOME_CHANGE_ACTIONS) {
            intentFilter.addAction(action);
        }
        broadcastDispatcher.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mDefaultHome = getCurrentDefaultHome();
            }
        }, intentFilter);
        mLauncherShowing = isLauncherShowing(ActivityManagerWrapper.getInstance().getRunningTask());
        TaskStackChangeListeners.getInstance().registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                        mLauncherShowing = isLauncherShowing(taskInfo);
                    }
        });
    }

    public int getPhoneState() {
        int phoneState;
        if (isShadeFullscreen()) {
            phoneState = getPhoneLockscreenState();
        } else if (mLauncherShowing) {
            phoneState = getPhoneLauncherState();
        } else {
            phoneState = PHONE_STATE_APP_IMMERSIVE;
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

    private boolean isShadeFullscreen() {
        int statusBarState = mStatusBarStateController.getState();
        return statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED;
    }

    private boolean isDozing() {
        return mStatusBarStateController.isDozing();
    }

    private boolean isLauncherShowing(@Nullable ActivityManager.RunningTaskInfo runningTaskInfo) {
        if (runningTaskInfo == null || runningTaskInfo.topActivity == null) {
            return false;
        } else {
            return runningTaskInfo.topActivity.equals(mDefaultHome);
        }
    }

    private boolean isBouncerShowing() {
        return mCentralSurfacesOptionalLazy.get()
                .map(CentralSurfaces::isBouncerShowing).orElse(false);
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
