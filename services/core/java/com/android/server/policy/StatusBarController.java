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
 * limitations under the License
 */

package com.android.server.policy;

import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import android.app.StatusBarManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.TranslateAnimation;

import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

/**
 * Implements status bar specific behavior.
 */
public class StatusBarController extends BarController {

    private final AppTransitionListener mAppTransitionListener
            = new AppTransitionListener() {

        @Override
        public void onAppTransitionPendingLocked() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    StatusBarManagerInternal statusbar = getStatusBarInternal();
                    if (statusbar != null) {
                        statusbar.appTransitionPending();
                    }
                }
            });
        }

        @Override
        public int onAppTransitionStartingLocked(int transit, IBinder openToken,
                IBinder closeToken, long duration, long statusBarAnimationStartTime,
                long statusBarAnimationDuration) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    StatusBarManagerInternal statusbar = getStatusBarInternal();
                    if (statusbar != null) {
                        statusbar.appTransitionStarting(statusBarAnimationStartTime,
                                statusBarAnimationDuration);
                    }
                }
            });
            return 0;
        }

        @Override
        public void onAppTransitionCancelledLocked(int transit) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    StatusBarManagerInternal statusbar = getStatusBarInternal();
                    if (statusbar != null) {
                        statusbar.appTransitionCancelled();
                    }
                }
            });
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    StatusBarManagerInternal statusbar = LocalServices.getService(
                            StatusBarManagerInternal.class);
                    if (statusbar != null) {
                        statusbar.appTransitionFinished();
                    }
                }
            });
        }
    };

    public StatusBarController() {
        super("StatusBar",
                View.STATUS_BAR_TRANSIENT,
                View.STATUS_BAR_UNHIDE,
                View.STATUS_BAR_TRANSLUCENT,
                StatusBarManager.WINDOW_STATUS_BAR,
                FLAG_TRANSLUCENT_STATUS,
                View.STATUS_BAR_TRANSPARENT);
    }


    public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
        StatusBarManagerInternal statusbar = getStatusBarInternal();
        if (statusbar != null) {
            statusbar.setTopAppHidesStatusBar(hidesStatusBar);
        }
    }

    @Override
    protected boolean skipAnimation() {
        return mWin.getAttrs().height == MATCH_PARENT;
    }

    public AppTransitionListener getAppTransitionListener() {
        return mAppTransitionListener;
    }
}
