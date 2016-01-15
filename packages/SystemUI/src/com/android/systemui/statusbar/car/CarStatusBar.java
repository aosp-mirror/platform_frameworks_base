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
import android.app.ITaskStackListener;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

/**
 * A status bar (and navigation bar) tailored for the automotive use case.
 */
public class CarStatusBar extends PhoneStatusBar {
    private SystemServicesProxy mSystemServicesProxy;
    private TaskStackListenerImpl mTaskStackListener;
    private Handler mHandler;

    private CarNavigationBarView mCarNavigationBar;
    private CarNavigationBarController mController;

    @Override
    public void start() {
        super.start();
        mHandler = new Handler();
        mTaskStackListener = new TaskStackListenerImpl(mHandler);
        mSystemServicesProxy = new SystemServicesProxy(mContext);
        mSystemServicesProxy.registerTaskStackListener(mTaskStackListener);
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

    @Override
    protected void repositionNavigationBar() {
        // The navigation bar for a vehicle will not need to be repositioned, as it is always
        // set at the bottom.
    }

    /**
     * An implementation of ITaskStackListener, that listens for changes in the system task
     * stack and notifies the navigation bar.
     */
    private class TaskStackListenerImpl extends ITaskStackListener.Stub implements Runnable {
        private Handler mHandler;

        public TaskStackListenerImpl(Handler handler) {
            this.mHandler = handler;
        }

        @Override
        public void onActivityPinned() {
        }

        @Override
        public void onTaskStackChanged() {
            mHandler.removeCallbacks(this);
            mHandler.post(this);
        }

        @Override
        public void run() {
            ensureMainThread();
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getTopMostTask();
            mController.taskChanged(runningTaskInfo.baseActivity.getPackageName());
        }

        private void ensureMainThread() {
            if (!Looper.getMainLooper().isCurrentThread()) {
                throw new RuntimeException("Must be called on the UI thread");
            }
        }
    }
}
