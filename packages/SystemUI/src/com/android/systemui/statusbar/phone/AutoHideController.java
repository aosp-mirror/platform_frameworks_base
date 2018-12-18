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
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationRemoteInputManager;

import javax.inject.Inject;
import javax.inject.Named;

/** A controller to control all auto-hide things. */
public class AutoHideController implements CommandQueue.Callbacks {
    private static final String TAG = "AutoHideController";

    private final IWindowManager mWindowManagerService;

    private final Handler mHandler;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final CommandQueue mCommandQueue;
    private StatusBar mStatusBar;
    private NavigationBarFragment mNavigationBar;

    private int mDisplayId;
    private int mSystemUiVisibility;
    // last value sent to window manager
    private int mLastDispatchedSystemUiVisibility = ~View.SYSTEM_UI_FLAG_VISIBLE;

    private boolean mAutoHideSuspended;

    private static final long AUTOHIDE_TIMEOUT_MS = 2250;

    private final Runnable mAutoHide = () -> {
        int requested = mSystemUiVisibility & ~getTransientMask();
        if (mSystemUiVisibility != requested) {
            notifySystemUiVisibilityChanged(requested);
        }
    };

    @Inject
    public AutoHideController(Context context, @Named(MAIN_HANDLER_NAME) Handler handler) {
        mCommandQueue = SysUiServiceProvider.getComponent(context, CommandQueue.class);
        mCommandQueue.addCallback(this);
        mHandler = handler;
        mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);
        mWindowManagerService = Dependency.get(IWindowManager.class);

        mDisplayId = context.getDisplayId();
    }

    void setStatusBar(StatusBar statusBar) {
        mStatusBar = statusBar;
    }

    void setNavigationBar(NavigationBarFragment navigationBar) {
        mNavigationBar = navigationBar;
    }

    @Override
    public void setSystemUiVisibility(int displayId, int vis, int fullscreenStackVis,
            int dockedStackVis, int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
        if (displayId != mDisplayId) {
            return;
        }
        int oldVal = mSystemUiVisibility;
        int newVal = (oldVal & ~mask) | (vis & mask);
        int diff = newVal ^ oldVal;

        if (diff != 0) {
            mSystemUiVisibility = newVal;

            // ready to unhide
            if (hasStatusBar() && (vis & View.STATUS_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.STATUS_BAR_UNHIDE;
            }

            if (hasNavigationBar() && (vis & View.NAVIGATION_BAR_UNHIDE) != 0) {
                mSystemUiVisibility &= ~View.NAVIGATION_BAR_UNHIDE;
            }

            // Re-send setSystemUiVisibility to update un-hide status.
            if (mSystemUiVisibility != newVal) {
                mCommandQueue.setSystemUiVisibility(mDisplayId, mSystemUiVisibility,
                        fullscreenStackVis, dockedStackVis, mask, fullscreenStackBounds,
                        dockedStackBounds);
            }

            notifySystemUiVisibilityChanged(mSystemUiVisibility);
        }
    }

    private void notifySystemUiVisibilityChanged(int vis) {
        try {
            if (mLastDispatchedSystemUiVisibility != vis) {
                mWindowManagerService.statusBarVisibilityChanged(mDisplayId, vis);
                mLastDispatchedSystemUiVisibility = vis;
            }
        } catch (RemoteException ex) {
            Log.w(TAG, "Cannot get WindowManager");
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
        mAutoHideSuspended = (mSystemUiVisibility & getTransientMask()) != 0;
    }

    void touchAutoHide() {
        // update transient bar auto hide
        if ((hasStatusBar() && mStatusBar.getStatusBarMode() == MODE_SEMI_TRANSPARENT)
                || hasNavigationBar() && mNavigationBar.isSemiTransparent()) {
            scheduleAutoHide();
        } else {
            cancelAutoHide();
        }
    }

    private Runnable getCheckBarModesRunnable() {
        if (hasStatusBar()) {
            return () -> mStatusBar.checkBarModes();
        } else if (hasNavigationBar()) {
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
        mHandler.postDelayed(mAutoHide, AUTOHIDE_TIMEOUT_MS);
    }

    void checkUserAutoHide(MotionEvent event) {
        boolean shouldAutoHide =
                (mSystemUiVisibility & getTransientMask()) != 0  // a transient bar is revealed.
                && event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar.
                && event.getX() == 0 && event.getY() == 0;
        if (hasStatusBar()) {
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

    private int getTransientMask() {
        int mask = 0;
        if (hasStatusBar()) {
            mask |= View.STATUS_BAR_TRANSIENT;
        }
        if (hasNavigationBar()) {
            mask |= View.NAVIGATION_BAR_TRANSIENT;
        }
        return mask;
    }

    private boolean hasNavigationBar() {
        return mNavigationBar != null;
    }

    private boolean hasStatusBar() {
        return mStatusBar != null;
    }
}
