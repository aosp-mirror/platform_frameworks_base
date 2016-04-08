/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewStub;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;

/**
 * A status bar (and navigation bar) tailored for the automotive use case.
 */
public class CarStatusBar extends PhoneStatusBar {
    private static final String TAG = "CarStatusBar";

    private SystemServicesProxy mSystemServicesProxy;
    private TaskStackListenerImpl mTaskStackListener;

    private CarNavigationBarView mCarNavigationBar;
    private CarNavigationBarController mController;
    private FullscreenUserSwitcher mFullscreenUserSwitcher;

    @Override
    public void start() {
        super.start();
        mTaskStackListener = new TaskStackListenerImpl();
        SystemServicesProxy.getInstance(mContext).registerTaskStackListener(mTaskStackListener);
        registerPackageChangeReceivers();
    }

    @Override
    protected void addNavigationBar() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("CarNavigationBar");
        lp.windowAnimations = 0;
        mWindowManager.addView(mNavigationBarView, lp);
    }

    @Override
    protected void createNavigationBarView(Context context) {
        if (mNavigationBarView != null) {
            return;
        }
        mCarNavigationBar =
                (CarNavigationBarView) View.inflate(context, R.layout.car_navigation_bar, null);
        mController = new CarNavigationBarController(context, mCarNavigationBar,
                this /* ActivityStarter*/);
        mNavigationBarView = mCarNavigationBar;

    }

    private BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null || mController == null) {
                return;
            }
            String packageName = intent.getData().getSchemeSpecificPart();
            mController.onPackageChange(packageName);
        }
    };

    private void registerPackageChangeReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mPackageChangeReceiver, filter);
    }

    @Override
    protected void repositionNavigationBar() {
        // The navigation bar for a vehicle will not need to be repositioned, as it is always
        // set at the bottom.
    }

    public boolean hasDockedTask() {
        return Recents.getSystemServices().hasDockedTask();
    }

    /**
     * An implementation of TaskStackListener, that listens for changes in the system task
     * stack and notifies the navigation bar.
     */
    private class TaskStackListenerImpl extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getTopMostTask();
            if (runningTaskInfo != null && runningTaskInfo.baseActivity != null) {
                mController.taskChanged(runningTaskInfo.baseActivity.getPackageName());
            }
        }
    }

    @Override
    protected void createUserSwitcher() {
        if (mUserSwitcherController.useFullscreenUserSwitcher()) {
            mFullscreenUserSwitcher = new FullscreenUserSwitcher(this, mUserSwitcherController,
                    (ViewStub) mStatusBarWindow.findViewById(R.id.fullscreen_user_switcher_stub));
        } else {
            super.createUserSwitcher();
        }
    }

    @Override
    public void userSwitched(int newUserId) {
        super.userSwitched(newUserId);
        if (mFullscreenUserSwitcher != null) {
            mFullscreenUserSwitcher.onUserSwitched(newUserId);
        }
    }

    @Override
    public void updateKeyguardState(boolean goingToFullShade, boolean fromShadeLocked) {
        super.updateKeyguardState(goingToFullShade, fromShadeLocked);
        if (mFullscreenUserSwitcher != null) {
            if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
                mFullscreenUserSwitcher.show();
            } else {
                mFullscreenUserSwitcher.hide();
            }
        }
    }

    private int startActivityWithOptions(Intent intent, Bundle options) {
        int result = ActivityManager.START_CANCELED;
        try {
            result = ActivityManagerNative.getDefault().startActivityAsUser(null /* caller */,
                    mContext.getBasePackageName(),
                    intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    null /* resultTo*/,
                    null /* resultWho*/,
                    0 /* requestCode*/,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    null /* profilerInfo*/,
                    options,
                    UserHandle.CURRENT.getIdentifier());
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to start activity", e);
        }

        return result;
    }

    public int startActivityOnStack(Intent intent, int stackId) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchStackId(stackId);
        return startActivityWithOptions(intent, options.toBundle());
    }
}
