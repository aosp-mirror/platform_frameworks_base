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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.IWindowManager;
import android.view.MotionEvent;

import com.android.systemui.statusbar.NotificationRemoteInputManager;

import javax.inject.Inject;
import javax.inject.Named;

/** A controller to control all auto-hide things. */
public class AutoHideController {
    private static final String TAG = "AutoHideController";

    private final IWindowManager mWindowManagerService;

    private final Handler mHandler;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private StatusBar mStatusBar;
    private NavigationBarFragment mNavigationBar;

    private int mDisplayId;

    private boolean mAutoHideSuspended;

    private static final long AUTO_HIDE_TIMEOUT_MS = 2250;

    private final Runnable mAutoHide = () -> {
        if (isAnyTransientBarShown()) {
            hideTransientBars();
        }
    };

    @Inject
    public AutoHideController(Context context, @Named(MAIN_HANDLER_NAME) Handler handler,
            NotificationRemoteInputManager notificationRemoteInputManager,
            IWindowManager iWindowManager) {
        mHandler = handler;
        mRemoteInputManager = notificationRemoteInputManager;
        mWindowManagerService = iWindowManager;

        mDisplayId = context.getDisplayId();
    }

    void setStatusBar(StatusBar statusBar) {
        mStatusBar = statusBar;
    }

    void setNavigationBar(NavigationBarFragment navigationBar) {
        mNavigationBar = navigationBar;
    }

    private void hideTransientBars() {
        try {
            mWindowManagerService.hideTransientBars(mDisplayId);
        } catch (RemoteException ex) {
            Log.w(TAG, "Cannot get WindowManager");
        }
        if (mStatusBar != null) {
            mStatusBar.clearTransient();
        }
        if (mNavigationBar != null) {
            mNavigationBar.clearTransient();
        }
    }

    void resumeSuspendedAutoHide() {
        if (mAutoHideSuspended) {
            scheduleAutoHide();
            Runnable checkBarModesRunnable = getCheckBarModesRunnable();
            if (checkBarModesRunnable != null) {
                mHandler.postDelayed(checkBarModesRunnable, 500); // longer than home -> launcher
            }
        }
    }

    void suspendAutoHide() {
        mHandler.removeCallbacks(mAutoHide);
        Runnable checkBarModesRunnable = getCheckBarModesRunnable();
        if (checkBarModesRunnable != null) {
            mHandler.removeCallbacks(checkBarModesRunnable);
        }
        mAutoHideSuspended = isAnyTransientBarShown();
    }

    void touchAutoHide() {
        // update transient bar auto hide
        if (isAnyTransientBarShown()) {
            scheduleAutoHide();
        } else {
            cancelAutoHide();
        }
    }

    private Runnable getCheckBarModesRunnable() {
        if (mStatusBar != null) {
            return () -> mStatusBar.checkBarModes();
        } else if (mNavigationBar != null) {
            return () -> mNavigationBar.checkNavBarModes();
        } else {
            return null;
        }
    }

    private void cancelAutoHide() {
        mAutoHideSuspended = false;
        mHandler.removeCallbacks(mAutoHide);
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        mHandler.postDelayed(mAutoHide, AUTO_HIDE_TIMEOUT_MS);
    }

    void checkUserAutoHide(MotionEvent event) {
        boolean shouldAutoHide = isAnyTransientBarShown()
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar.
                && event.getX() == 0 && event.getY() == 0;
        if (mStatusBar != null) {
            // a touch outside both bars
            shouldAutoHide &= !mRemoteInputManager.getController().isRemoteInputActive();
        }
        if (shouldAutoHide) {
            userAutoHide();
        }
    }

    private void userAutoHide() {
        cancelAutoHide();
        mHandler.postDelayed(mAutoHide, 350); // longer than app gesture -> flag clear
    }

    private boolean isAnyTransientBarShown() {
        return (mStatusBar != null && mStatusBar.isTransientShown())
                || mNavigationBar != null && mNavigationBar.isTransientShown();
    }
}
