/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.AutoHideUiElement;

import java.io.PrintWriter;

import javax.inject.Inject;

public class AutoHideControllerImpl implements AutoHideController {
    private static final String TAG = "AutoHideController";
    private static final int AUTO_HIDE_TIMEOUT_MS = 2250;
    private static final int USER_AUTO_HIDE_TIMEOUT_MS = 350;

    private final AccessibilityManager mAccessibilityManager;
    private final IWindowManager mWindowManagerService;
    private final Handler mHandler;

    private AutoHideUiElement mStatusBar;
    /** For tablets, this will represent the Taskbar */
    private AutoHideUiElement mNavigationBar;
    private int mDisplayId;

    private boolean mAutoHideSuspended;

    private final Runnable mAutoHide = () -> {
        if (isAnyTransientBarShown()) {
            hideTransientBars();
        }
    };

    @Inject
    public AutoHideControllerImpl(Context context,
            @Main Handler handler,
            IWindowManager iWindowManager) {
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mHandler = handler;
        mWindowManagerService = iWindowManager;
        mDisplayId = context.getDisplayId();
    }

    @Override
    public void setStatusBar(AutoHideUiElement element) {
        mStatusBar = element;
    }

    @Override
    public void setNavigationBar(AutoHideUiElement element) {
        mNavigationBar = element;
    }

    private void hideTransientBars() {
        try {
            mWindowManagerService.hideTransientBars(mDisplayId);
        } catch (RemoteException ex) {
            Log.w(TAG, "Cannot get WindowManager");
        }

        if (mStatusBar != null) {
            mStatusBar.hide();
        }

        if (mNavigationBar != null) {
            mNavigationBar.hide();
        }
    }

    @Override
    public void resumeSuspendedAutoHide() {
        if (mAutoHideSuspended) {
            scheduleAutoHide();
            Runnable checkBarModesRunnable = getCheckBarModesRunnable();
            if (checkBarModesRunnable != null) {
                mHandler.postDelayed(checkBarModesRunnable, 500); // longer than home -> launcher
            }
        }
    }

    @Override
    public void suspendAutoHide() {
        mHandler.removeCallbacks(mAutoHide);
        Runnable checkBarModesRunnable = getCheckBarModesRunnable();
        if (checkBarModesRunnable != null) {
            mHandler.removeCallbacks(checkBarModesRunnable);
        }
        mAutoHideSuspended = isAnyTransientBarShown();
    }

    @Override
    public void touchAutoHide() {
        // update transient bar auto hide
        if (isAnyTransientBarShown()) {
            scheduleAutoHide();
        } else {
            cancelAutoHide();
        }
    }

    private Runnable getCheckBarModesRunnable() {
        if (mStatusBar != null) {
            return () -> mStatusBar.synchronizeState();
        } else if (mNavigationBar != null) {
            return () -> mNavigationBar.synchronizeState();
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
        mHandler.postDelayed(mAutoHide, getAutoHideTimeout());
    }

    private int getAutoHideTimeout() {
        return mAccessibilityManager.getRecommendedTimeoutMillis(AUTO_HIDE_TIMEOUT_MS,
                FLAG_CONTENT_CONTROLS);
    }

    @Override
    public void checkUserAutoHide(MotionEvent event) {
        boolean shouldHide = isAnyTransientBarShown()
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar.
                && event.getX() == 0 && event.getY() == 0;

        if (mStatusBar != null) {
            shouldHide &= mStatusBar.shouldHideOnTouch();
        }
        if (mNavigationBar != null) {
            shouldHide &= mNavigationBar.shouldHideOnTouch();
        }

        if (shouldHide) {
            userAutoHide();
        }
    }

    private void userAutoHide() {
        cancelAutoHide();
        // longer than app gesture -> flag clear
        mHandler.postDelayed(mAutoHide, getUserAutoHideTimeout());
    }

    private int getUserAutoHideTimeout() {
        return mAccessibilityManager.getRecommendedTimeoutMillis(USER_AUTO_HIDE_TIMEOUT_MS,
                FLAG_CONTENT_CONTROLS);
    }

    private boolean isAnyTransientBarShown() {
        if (mStatusBar != null && mStatusBar.isVisible()) {
            return true;
        }

        if (mNavigationBar != null && mNavigationBar.isVisible()) {
            return true;
        }

        return false;
    }

    @Override
    public void stop() {
        mHandler.removeCallbacks(mAutoHide);
    }

    @Override
    public void dump(@NonNull PrintWriter pw) {
        pw.println("AutoHideController:");
        pw.println("\tmAutoHideSuspended=" + mAutoHideSuspended);
        pw.println("\tisAnyTransientBarShown=" + isAnyTransientBarShown());
        pw.println("\thasPendingAutoHide=" + mHandler.hasCallbacks(mAutoHide));
        pw.println("\tgetAutoHideTimeout=" + getAutoHideTimeout());
        pw.println("\tgetUserAutoHideTimeout=" + getUserAutoHideTimeout());
    }

    public static class Factory implements AutoHideController.Factory {
        private final Handler mHandler;
        private final IWindowManager mIWindowManager;

        @Inject
        public Factory(@Main Handler handler, IWindowManager iWindowManager) {
            mHandler = handler;
            mIWindowManager = iWindowManager;
        }

        /** Create an {@link AutoHideController} */
        @Override
        public AutoHideController create(Context context) {
            return new AutoHideControllerImpl(context, mHandler, mIWindowManager);
        }
    }
}
