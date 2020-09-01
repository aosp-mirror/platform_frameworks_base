/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import android.app.StatusBarManager;
import android.os.IBinder;
import android.view.View;

import com.android.server.statusbar.StatusBarManagerInternal;

/**
 * Implements status bar specific behavior.
 */
public class StatusBarController extends BarController {

    private final AppTransitionListener mAppTransitionListener = new AppTransitionListener() {

        private Runnable mAppTransitionPending = () -> {
            StatusBarManagerInternal statusBar = getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionPending(mDisplayId);
            }
        };

        private Runnable mAppTransitionCancelled = () -> {
            StatusBarManagerInternal statusBar = getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionCancelled(mDisplayId);
            }
        };

        private Runnable mAppTransitionFinished = () -> {
            StatusBarManagerInternal statusBar = getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionFinished(mDisplayId);
            }
        };

        @Override
        public void onAppTransitionPendingLocked() {
            mHandler.post(mAppTransitionPending);
        }

        @Override
        public int onAppTransitionStartingLocked(int transit, long duration,
                long statusBarAnimationStartTime, long statusBarAnimationDuration) {
            mHandler.post(() -> {
                StatusBarManagerInternal statusBar = getStatusBarInternal();
                if (statusBar != null) {
                    statusBar.appTransitionStarting(mDisplayId,
                            statusBarAnimationStartTime, statusBarAnimationDuration);
                }
            });
            return 0;
        }

        @Override
        public void onAppTransitionCancelledLocked(int transit) {
            mHandler.post(mAppTransitionCancelled);
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            mHandler.post(mAppTransitionFinished);
        }
    };

    StatusBarController(int displayId) {
        super("StatusBar",
                displayId,
                View.STATUS_BAR_TRANSIENT,
                View.STATUS_BAR_UNHIDE,
                View.STATUS_BAR_TRANSLUCENT,
                StatusBarManager.WINDOW_STATUS_BAR,
                TYPE_STATUS_BAR,
                FLAG_TRANSLUCENT_STATUS,
                View.STATUS_BAR_TRANSPARENT);
    }

    void setTopAppHidesStatusBar(boolean hidesStatusBar) {
        StatusBarManagerInternal statusBar = getStatusBarInternal();
        if (statusBar != null) {
            statusBar.setTopAppHidesStatusBar(hidesStatusBar);
        }
    }

    AppTransitionListener getAppTransitionListener() {
        return mAppTransitionListener;
    }
}
